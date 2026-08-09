package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.files.InstanceFiles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.InstanceFileSupport;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The file manager against a REAL daemon and a REAL container, because every claim it
 * makes is about what the DAEMON does, not about what our code intended.
 *
 * The counterfactual is built INTO the escape steps rather than bolted on: each traversal
 * and symlink attempt is followed by the SAME path asked of the daemon directly, and that
 * direct read SUCCEEDS. A refusal beside a demonstrated escape is a refusal that is doing
 * work; a refusal beside a path that never resolved anywhere would be a vacuous test.
 */
class InstanceFilesLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    /** The cap the bound counterfactual is measured against; small so the test stays cheap. */
    private static final int MAX_FILE_KB = 64;

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;
    private static Integer previousMaxFileKb;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-instance-files-live-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        // BEFORE the boot: the capability matrix below grants files.read/files.write, and
        // zenit-auth refuses a grant on an undeclared model -- while declaring one AFTER the
        // CMS contributions drained is itself a hard failure. Same order ServerMain uses.
        HohenheimAccess.declareGrantableModels();
        HohenheimTestRuntime.ensureBooted();
        previousMaxFileKb = HohenheimSettings.VALUES.getValue(HohenheimSettings.Files.MAX_FILE_KB);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Files.MAX_FILE_KB, MAX_FILE_KB);
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Files.MAX_FILE_KB, previousMaxFileKb);
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    /**
     * One journey through browse, read, edit, upload, mkdir, rename, delete, and then
     * every named escape attempt against a container that really holds the escapes.
     */
    @Test
    void fileManagerJourneyAndEveryContainmentRefusalAgainstTheRealDaemon() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        AtomicReference<String> handleRef = new AtomicReference<>();
        AtomicReference<String> volumeRef = new AtomicReference<>();
        try {
            Db.run(datasource, () -> {
                HostFixtures.admitLocal();
                int id = instanceRecord("files-journey", volumeSettings());
                String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
                handleRef.set(handle);
                volumeRef.set(handle + "-vol-data");

                new InstanceService().deploy(id);
                InstanceFiles files = new InstanceFiles();

                // 1. The volume root browses, and it is the ONLY declared root.
                InstanceFiles.Listing roots = files.list(id, null);
                assertThat(roots.volumeRoots())
                    .as("step 1: the instance's own volume is the only browse root")
                    .containsExactly("/data");
                assertThat(roots.path())
                    .as("step 1: an empty request lands on the volume root")
                    .isEqualTo("/data");

                // 2. Seed real content INSIDE the container, then prove the listing sees
                //    exactly it -- kinds, sizes and the symlink AS a symlink.
                seed(docker, handle);
                InstanceFiles.Listing listing = files.list(id, "/data");
                assertThat(listing.entries().stream().map(InstanceFiles.Entry::name).toList())
                    .as("step 2: the listing is the immediate children, directories first")
                    .containsExactly("sub", "escape", "etcdir", "hello.txt");
                assertThat(kindOf(listing, "hello.txt"))
                    .as("step 2: a regular file reads as a file").isEqualTo("FILE");
                assertThat(kindOf(listing, "escape"))
                    .as("step 2: a symlink reads as a SYMLINK and is never followed")
                    .isEqualTo("SYMLINK");
                assertThat(sizeOf(listing, "hello.txt"))
                    .as("step 2: the size is the daemon's, not a guess").isEqualTo(6);

                // 3. Read: the bytes are the file's, asserted against what the container
                //    itself reports -- never against what we just wrote.
                assertThat(files.readText(id, "/data/hello.txt"))
                    .as("step 3: read returns the file's real bytes")
                    .isEqualTo("hello\n");

                // 4. Write, then read the file back FROM INSIDE the container: an API that
                //    reported success while writing nothing is the defect shape this
                //    codebase hunts by name.
                files.write(id, "/data/hello.txt", "rewritten\n".getBytes(StandardCharsets.UTF_8));
                assertThat(inContainer(docker, handle, "cat /data/hello.txt"))
                    .as("step 4: the container itself sees the new content")
                    .isEqualTo("rewritten\n");

                // 5. Create: a new file in a new directory, both observed in-container.
                files.makeDirectory(id, "/data/made");
                files.write(id, "/data/made/new.txt", "fresh".getBytes(StandardCharsets.UTF_8));
                assertThat(inContainer(docker, handle, "cat /data/made/new.txt"))
                    .as("step 5: mkdir + write land where they claimed to")
                    .isEqualTo("fresh");
                assertThat(catchThrowable(() -> files.makeDirectory(id, "/data/made")))
                    .as("step 5: mkdir over an existing path REFUSES, never silently succeeds")
                    .isInstanceOf(Violations.class);

                // 6. Rename and delete, each verified at the container.
                files.rename(id, "/data/made/new.txt", "/data/made/renamed.txt");
                assertThat(inContainer(docker, handle, "cat /data/made/renamed.txt"))
                    .as("step 6: rename moved the bytes").isEqualTo("fresh");
                files.delete(id, "/data/made");
                assertThat(inContainer(docker, handle, "test -e /data/made && echo yes || echo no"))
                    .as("step 6: delete removed the tree").isEqualTo("no\n");

                // ---- COUNTERFACTUAL 1: traversal is refused, and it WOULD have worked ----
                //
                // The daemon resolves /data/../etc/passwd happily; only our lexical layer
                // stops it. Proving the daemon serves it is what makes the refusal
                // meaningful rather than a check over a path that resolves nowhere.
                String daemonPasswd = new String(
                    daemonRead(docker, handle, "/data/../etc/passwd"), StandardCharsets.UTF_8);
                assertThat(daemonPasswd)
                    .as("counterfactual 1: the DAEMON does serve /data/../etc/passwd")
                    .contains("root:x:0:0");
                for (String traversal : List.of("/data/../etc/passwd", "/etc/passwd",
                        "/data/../../etc/passwd", "/data/..")) {
                    Throwable refused = catchThrowable(() -> files.read(id, traversal));
                    assertThat(keyOf(refused))
                        .as("counterfactual 1: the file manager refuses '" + traversal + "'")
                        .isEqualTo("files_path_refused");
                }
                // And the read that WOULD have escaped returned nothing at all: assert on
                // the payload, never on a status code.
                assertThat(catchThrowable(() -> files.read(id, "/data/../etc/passwd")))
                    .as("counterfactual 1: nothing outside the volume is ever returned")
                    .isInstanceOf(Violations.class);

                // ---- COUNTERFACTUAL 2: a symlink escaping the volume is refused ----
                //
                // Two shapes, and the second is the one a naive check misses: statting the
                // LEAF of /data/etcdir/passwd reports an ordinary file, because the daemon
                // already resolved the symlinked COMPONENT.
                String daemonThroughLink = new String(
                    daemonRead(docker, handle, "/data/etcdir/passwd"), StandardCharsets.UTF_8);
                assertThat(daemonThroughLink)
                    .as("counterfactual 2: the DAEMON resolves through a symlinked component")
                    .contains("root:x:0:0");
                DockerClient.PathStat leafStat = daemonStat(docker, handle, "/data/etcdir/passwd");
                assertThat(leafStat.isSymlink())
                    .as("counterfactual 2: and the LEAF stat shows no symlink at all -- "
                        + "a leaf-only check could not possibly catch this")
                    .isFalse();
                assertThat(keyOf(catchThrowable(() -> files.read(id, "/data/etcdir/passwd"))))
                    .as("counterfactual 2: the component walk refuses the symlinked directory")
                    .isEqualTo("files_path_refused");
                assertThat(keyOf(catchThrowable(() -> files.read(id, "/data/escape"))))
                    .as("counterfactual 2: and a symlink LEAF is never followed either")
                    .isEqualTo("files_not_a_file");
                assertThat(keyOf(catchThrowable(() ->
                        files.write(id, "/data/etcdir/passwd", "x".getBytes(StandardCharsets.UTF_8)))))
                    .as("counterfactual 2: the WRITE lane walks the same components")
                    .isEqualTo("files_path_refused");
                assertThat(inContainer(docker, handle, "cat /etc/passwd | head -1"))
                    .as("counterfactual 2: /etc/passwd inside the container is untouched")
                    .startsWith("root:x:0:0");

                // ---- COUNTERFACTUAL 4: bounds are enforced DURING the read ----
                //
                // A 512 KiB file against the 64 KiB cap. The driver call is made directly so the
                // service's cheap pre-check cannot be what refuses: this asserts the
                // TRANSFER aborts, and that nothing partial comes back.
                inContainer(docker, handle, "dd if=/dev/zero of=/data/big.bin bs=1024 count=512");
                InstanceFileSupport driver = (InstanceFileSupport)
                    new InstanceService().resolve(id).runtime();
                Throwable overSize = catchThrowable(() ->
                    driver.readFile(handle, "/data/big.bin", MAX_FILE_KB * 1024L));
                assertThat(overSize)
                    .as("counterfactual 4: an over-cap transfer THROWS instead of truncating")
                    .isInstanceOf(IOException.class);
                assertThat(keyOf(catchThrowable(() -> files.read(id, "/data/big.bin"))))
                    .as("counterfactual 4: and the service refuses it by name")
                    .isEqualTo("files_too_large");
                byte[] oversized = new byte[(int) InstanceFiles.maxFileBytes() + 1];
                assertThat(keyOf(catchThrowable(() ->
                        files.write(id, "/data/oversized.bin", oversized))))
                    .as("counterfactual 4: an over-cap upload is refused")
                    .isEqualTo("files_too_large");
                assertThat(inContainer(docker, handle,
                        "test -e /data/oversized.bin && echo yes || echo no"))
                    .as("counterfactual 4: and NO partial artifact survives in the container")
                    .isEqualTo("no\n");

                // 7. Managed config files are read-only here: deploy re-stages them, so a
                //    hand edit would silently revert at the next start.
                Row managed = Models.get(InstanceFileModel.class).createEmptyRow();
                managed.set(InstanceFileModel.INSTANCE_ID, id);
                managed.set(InstanceFileModel.CONTAINER_PATH, "/data/hello.txt");
                managed.set(InstanceFileModel.CONTENT, "managed");
                managed.set(InstanceFileModel.MODE, "0644");
                Models.get(InstanceFileModel.class).save(managed);
                assertThat(keyOf(catchThrowable(() ->
                        files.write(id, "/data/hello.txt", "x".getBytes(StandardCharsets.UTF_8)))))
                    .as("step 7: a declared config file refuses a file-manager write BY NAME")
                    .isEqualTo("files_managed_config");
                assertThat(inContainer(docker, handle, "cat /data/hello.txt"))
                    .as("step 7: and the container's copy is untouched by the refusal")
                    .isEqualTo("rewritten\n");
                Models.get(InstanceFileModel.class).delete(managed);

                // ---- COUNTERFACTUAL 3 and 5: capabilities and cross-tenant ----
                capabilityAndTenancyMatrix(id, files);

                new InstanceService().destroy(id);
            });
        } finally {
            cleanup(docker, handleRef.get(), volumeRef.get());
        }
    }

    /**
     * Counterfactual 3 ({@code files.read} does not imply {@code files.write}) and
     * counterfactual 5 (another tenant's instance is indistinguishable from a missing one).
     */
    private static void capabilityAndTenancyMatrix(int instanceId, InstanceFiles files) {
        int reader = tenant("files-reader@live.test");
        int writer = tenant("files-writer@live.test");
        int stranger = tenant("files-stranger@live.test");
        RecordGrants.grant("user", reader, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.FILES_READ, true);
        RecordGrants.grant("user", writer, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.FILES_READ, true);
        RecordGrants.grant("user", writer, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.FILES_WRITE, true);

        // 1. The read-capable tenant reads.
        TenantConduits.as(principal(reader), () ->
            assertThat(files.readText(instanceId, "/data/hello.txt"))
                .as("counterfactual 3: files.read alone is enough to READ")
                .isEqualTo("rewritten\n"));

        // 2. ...and cannot write, rename, delete or mkdir. The refusal is the named
        //    capability violation, and the file is unchanged after every one of them.
        for (String verb : List.of("write", "mkdir", "rename", "delete")) {
            TenantConduits.as(principal(reader), () -> {
                Throwable refused = catchThrowable(() -> {
                    switch (verb) {
                        case "write" -> files.write(instanceId, "/data/hello.txt",
                            "hijacked".getBytes(StandardCharsets.UTF_8));
                        case "mkdir" -> files.makeDirectory(instanceId, "/data/by-reader");
                        case "rename" -> files.rename(instanceId, "/data/hello.txt",
                            "/data/moved.txt");
                        default -> files.delete(instanceId, "/data/hello.txt");
                    }
                });
                assertThat(keyOf(refused))
                    .as("counterfactual 3: files.read does NOT imply files.write ('" + verb + "')")
                    .isEqualTo("instance_not_permitted");
            });
        }
        assertThat(files.readText(instanceId, "/data/hello.txt"))
            .as("counterfactual 3: nothing the reader attempted changed a single byte")
            .isEqualTo("rewritten\n");

        // 3. The write-capable tenant writes -- so the refusals above are about the
        //    CAPABILITY and not about the tenant lane being broken for everyone.
        TenantConduits.as(principal(writer), () ->
            files.write(instanceId, "/data/hello.txt", "by-writer".getBytes(StandardCharsets.UTF_8)));
        assertThat(files.readText(instanceId, "/data/hello.txt"))
            .as("counterfactual 3: files.write DOES write (the negative is not vacuous)")
            .isEqualTo("by-writer");

        // 4. A stranger holding nothing on this instance gets the SAME named refusal for
        //    the real instance as for an id that does not exist at all.
        int missingId = instanceId + 987_654;
        assertThat(Models.get(InstanceModel.class).findById(missingId))
            .as("counterfactual 5: the control id really is absent").isNull();
        TenantConduits.as(principal(stranger), () -> {
            String forReal = keyOf(catchThrowable(() -> files.list(instanceId, "/data")));
            String forMissing = keyOf(catchThrowable(() -> files.list(missingId, "/data")));
            assertThat(forReal)
                .as("counterfactual 5: another tenant's instance refuses by capability")
                .isEqualTo("instance_not_permitted");
            assertThat(forReal)
                .as("counterfactual 5: and a nonexistent id answers IDENTICALLY -- no oracle")
                .isEqualTo(forMissing);
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static Map<String, Object> volumeSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 600");
        settings.put("volumes", Map.of("data", "/data"));
        return settings;
    }

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int tenant(String email) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static Principal principal(int userId) {
        return new UserPrincipal((long) userId, "user-" + userId);
    }

    /** The content and the two escapes the containment steps are asked to stop. */
    private static void seed(DockerClient docker, String handle) {
        inContainer(docker, handle, "mkdir -p /data/sub && printf 'hello\\n' > /data/hello.txt"
            + " && ln -sf /etc/shadow /data/escape && ln -sfn /etc /data/etcdir");
    }

    /** The archive read our containment layers are NOT applied to; the counterfactual oracle. */
    private static byte[] daemonRead(DockerClient docker, String handle, String path) {
        try {
            return docker.getArchiveFile(handle, path, 1_000_000);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The daemon's own lstat of a path, likewise ungated. */
    private static DockerClient.PathStat daemonStat(DockerClient docker, String handle, String path) {
        try {
            return docker.statArchivePath(handle, path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Run a command INSIDE the container and return its stdout; the assertion oracle. */
    private static String inContainer(DockerClient docker, String handle, String script) {
        try {
            DockerClient.ExecResult result = docker.exec(handle, List.of("/bin/sh", "-c", script));
            return result.stdout();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String kindOf(InstanceFiles.Listing listing, String name) {
        return listing.entries().stream().filter(entry -> entry.name().equals(name))
            .map(InstanceFiles.Entry::kind).findFirst().orElse("ABSENT");
    }

    private static long sizeOf(InstanceFiles.Listing listing, String name) {
        return listing.entries().stream().filter(entry -> entry.name().equals(name))
            .mapToLong(InstanceFiles.Entry::size).findFirst().orElse(-1);
    }

    /** The MACHINE KEY of a refusal; "" for anything that is not a named violation. */
    private static String keyOf(Throwable thrown) {
        if (!(thrown instanceof Violations violations)) {
            return thrown == null ? "" : thrown.getClass().getSimpleName();
        }
        List<Violation> all = violations.all();
        return all.isEmpty() ? "" : all.get(0).message().key();
    }

    private static void cleanup(DockerClient docker, String container, String volume) {
        if (container == null) {
            return;
        }
        try {
            docker.removeContainer(container, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(WorkloadNetworks.networkName(container));
        } catch (IOException ignored) {
            // a deploy that never reached the network has none to remove
        }
        if (volume != null) {
            try {
                docker.removeVolume(volume, true);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
