package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.ApiWire;
import be.elevenways.hohenheim.test.ApiWire.Caller;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.TestImages;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ApiSupport.form;

/**
 * The file routes of {@code /api/v1} against a REAL container: every route's success reply and its main refusal,
 * compared byte for byte to the java-rewrite capture through {@link ApiWire}.
 *
 * AIDEV-NOTE: the file lane has one driver, Docker's, so its success replies need a daemon, a private netns (the
 * instance tier refuses to deploy unprotected) and the pinned Alpine image; {@link LiveLane} names each need it
 * skips on. The volume's content is seeded inside the container with every modification time set to {@link #T0},
 * so a listing carries no clock; the class runs on a database of its own, so every id is the same on every run.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@Tag("slow") // live lane: needs a real daemon, a private netns and an image; runs via `zenit-dev test --all`
class ApiWireFilesLiveTest extends HohenheimTestBase {

    private static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");
    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static PrivateNetns netns;
    private static Caller tenant;
    private static Caller viewer;
    private static int instanceId;
    private static String handle;

    @BeforeAll
    static void seed() throws Exception {
        TestDatabases.freshDatabase();
        netns = PrivateNetns.installEnforcing();
        HostFixtures.admitLocal();

        ApiWire.operatorId();
        int tenantId = ApiSupport.user("wire-files-tenant@surface.test", "Wire Files Tenant");
        int viewerId = ApiSupport.user("wire-files-viewer@surface.test", "Wire Files Viewer");

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", TestImages.ALPINE);
        settings.put("command", "sleep 600");
        settings.put("volumes", Map.of("data", "/data"));
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "wire-files");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.CREATED_AT, T0);
        Models.get(InstanceModel.class).save(row);
        instanceId = row.get(InstanceModel.ID);
        handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);

        for (String capability : List.of(HohenheimCapabilities.MANAGE, HohenheimCapabilities.FILES_READ,
                HohenheimCapabilities.FILES_WRITE)) {
            RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId, capability, true);
        }
        RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.VIEW, true);
        List<String> scopes = List.of(
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimCapabilities.MANAGE),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimCapabilities.FILES_READ),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimCapabilities.FILES_WRITE));
        tenant = new Caller.Key(ApiKeyService.create(tenantId, "wire-files-tenant", scopes, null).plaintext());
        // The viewer's key carries every scope the tenant's does: its owner's VIEW grant is what refuses it.
        viewer = new Caller.Key(ApiKeyService.create(viewerId, "wire-files-viewer", scopes, null).plaintext());
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (handle != null) {
            DockerClient docker = new DockerClient();
            try {
                docker.removeContainer(handle, true);
            } catch (IOException ignored) {
                // never deployed, or already gone
            }
            try {
                docker.removeNetwork(WorkloadNetworks.networkName(handle));
            } catch (IOException ignored) {
                // a deploy that never reached the network has none to remove
            }
            try {
                docker.removeVolume(handle + "-vol-data", true);
            } catch (IOException ignored) {
                // never created, or already gone
            }
        }
        PrivateNetns.uninstall(netns);
        netns = null;
        // Later classes of this JVM find the seeded shared database they expect.
        freshSeededDatabase();
    }

    @Test
    void theFileRoutesAnswerWhatJavaRewriteAnswered() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TestImages.ALPINE);
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        // 1. A deployed container whose volume holds one directory and one file, every timestamp T0.
        new InstanceService().deploy(instanceId);
        DockerClient.ExecResult seeded = docker.exec(handle, List.of("/bin/sh", "-c",
            "mkdir -p /data/sub && printf 'hello\\n' > /data/hello.txt"
                + " && touch -d '2026-01-02 03:04:05' /data/hello.txt /data/sub /data"));
        if (seeded.exitCode() != 0) {
            throw new IllegalStateException("the volume could not be seeded: " + seeded.output());
        }

        ApiWire wire = new ApiWire("files", this::requestTo, HohenheimTestBase::sendRequestBytes);
        String files = "/api/v1/instances/" + instanceId + "/files";

        // 2. The reads: the volume root's listing and one file's bytes, refused without files.read and without a path.
        wire.get("list the volume root", tenant, files + "?path=/data");
        wire.get("list without files.read", viewer, files + "?path=/data");
        wire.get("read a file", tenant, files + "/content?path=/data/hello.txt");
        wire.get("read without a path", tenant, files + "/content");

        // 3. The writes: a file and a directory, refused for a path that leaves the volume and an unknown action.
        wire.post("write a file", tenant, files + "/content", form("path", "/data/written.txt",
                "content", "written\n"));
        wire.post("write outside the volume", tenant, files + "/content",
            form("path", "/data/../etc/passwd", "content", "x"));
        wire.post("make a directory", tenant, files + "/action", form("action", "mkdir", "path", "/data/made"));
        wire.post("an unknown file action", tenant, files + "/action", form("action", "chmod", "path", "/data/made"));

        // 4. Every reply is the java-rewrite one.
        wire.assertGolden();
    }
}
