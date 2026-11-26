package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.PinnedImages;
import be.elevenways.hohenheim.server.files.VolumeHelper;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.hohenheim.test.docker.FakeContainerFiles;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static be.elevenways.hohenheim.test.ApiSupport.codeOf;
import static be.elevenways.hohenheim.test.ApiSupport.form;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Declare a volume, put a file into it and deploy, with no host shell: the declaration creates the directory, the put
 * reaches it through a short-lived helper container that binds only the app's own volumes while nothing serves the
 * app, and the first release mounts that same directory.
 *
 * AIDEV-NOTE: hermetic. The helper's daemon is {@link FakeContainerFiles} (its filesystem stands for the bound volume
 * directory), the release's is {@link FakeDockerDaemon}, and the host volume storage is {@link FakeHostVolumes}. What
 * ties the three together is the HOST PATH: the helper binds {@code InstanceVolumes.hostPathFor}, the release's
 * stored mounts name the same path, and the fake host storage holds the directory under it. No real kernel or image
 * is exercised; the live proof of a bind is ContainerHardeningTest's.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class InstanceVolumePutJourneyTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "volume-put-";
    private static final String DATABASE = "SQLite format 3\u0000 the app's own database";

    private static int appId;
    private static String adminKey;
    private static String tenantKey;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;
    private static Integer savedMaxFileKb;

    @BeforeAll
    static void seed() {
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, PREFIX + "app");
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.HOST_ID, HostModel.localHostId());
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", "v1");
        settings.put("container_port", 8080);
        app.set(InstanceModel.SETTINGS, settings);
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(app);
        appId = app.get(InstanceModel.ID);

        // A tenant who may write the app's files, and still is no operator: the put is the Volumes tab's.
        UUID tenant = ApiSupport.user(PREFIX + "tenant@surface.test", "Volume Put Tenant");
        List<String> scopes = List.of(
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimCapabilities.MANAGE),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimCapabilities.FILES_READ),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimCapabilities.FILES_WRITE));
        for (String capability : List.of(HohenheimCapabilities.MANAGE, HohenheimCapabilities.FILES_READ,
                HohenheimCapabilities.FILES_WRITE)) {
            RecordGrants.grant(GrantSubjectType.USER, tenant, InstanceModel.MODEL_ID, appId, capability, true);
        }
        tenantKey = ApiKeyService.create(tenant, PREFIX + "tenant", scopes, null).plaintext();
        UUID adminId = Models.get(UserModel.class).find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        adminKey = ApiKeyService.create(adminId, PREFIX + "admin", List.of("hohenheim.*"), null).plaintext();

        HostFixtures.admitLocal();
        savedProbeTimeout = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        savedMaxFileKb = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Files.MAX_FILE_KB);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 5);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
    }

    @AfterAll
    static void cleanUp() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Files.MAX_FILE_KB, savedMaxFileKb);
        Models.get(InstanceVolumeModel.class).find().where(InstanceVolumeModel.INSTANCE_ID.eq(appId)).delete();
        HardDeletes.byId(Models.get(InstanceModel.class), appId);
    }

    /** Journey: declare, the put's refusals, the put through the helper, then a first deploy that mounts it. */
    @Test
    void aFilePutBeforeTheFirstDeployIsInTheVolumeTheReleaseMounts() throws Exception {
        String volumes = "/api/v1/instances/" + appId + "/volumes";
        String hostPath = InstanceVolumes.hostPathFor(appId, "data");
        String server = HostModel.nameOf(HostModel.localHostId());
        FakeHostVolumes hosts = FakeHostVolumes.install();
        // Two exited helpers a failed removal left behind: one of this app's, one of another app's.
        String leftover = ControllerScope.kindPrefix(ControllerScope.KIND_FILES) + appId + "-0";
        String othersLeftover = ControllerScope.kindPrefix(ControllerScope.KIND_FILES) + (appId + 1_000_000) + "-0";
        FakeContainerFiles helperDaemon = FakeContainerFiles.createdByCaller().directory("/data", "1001:1002")
            .symlink("/data/link", "/etc")
            .exited(leftover, OwnerLabels.of(InstanceModel.MODEL_ID, appId))
            .exited(othersLeftover, OwnerLabels.of(InstanceModel.MODEL_ID, appId + 1_000_000));
        DockerClient.overrideLocalTransportForTest(() -> helperDaemon);
        FakeDockerDaemon releaseDaemon = new FakeDockerDaemon();
        try {
            // 1. Declared: the directory exists at once, before anything of the app ever ran.
            HttpResponse<String> declared = keyPost(adminKey, volumes, form("name", "data", "container_path", "/data"));
            assertThat(declared.statusCode()).as("step 1: declared (%s)", declared.body()).isEqualTo(200);
            assertThat(hosts.directoryOf(server, hostPath)).as("step 1: its directory is on the host")
                .isDirectory();
            assertThat(ApplicationReleases.ownedServing(appId)).as("step 1: and nothing serves the app").isNull();

            // 2. Refused, each before a helper exists: no key, a tenant who may write the app's files but is no
            //    operator (for a declared and an undeclared volume alike: the refusal on the app comes before the
            //    lookup), an undeclared volume, a path that is no canonical relative one, and a body over the cap.
            assertThat(sendRequest(requestTo(putRoute("data", "app.sqlite"))
                .POST(HttpRequest.BodyPublishers.ofString(DATABASE))).statusCode())
                .as("step 2: no key").isEqualTo(401);
            assertThat(put(tenantKey, "data", "app.sqlite", DATABASE).statusCode())
                .as("step 2: the tenant is refused").isEqualTo(403);
            assertThat(put(tenantKey, "nope", "app.sqlite", DATABASE).statusCode())
                .as("step 2: and learns nothing of the volume names").isEqualTo(403);
            assertThat(put(adminKey, "nope", "app.sqlite", DATABASE).statusCode())
                .as("step 2: an undeclared volume is the 404").isEqualTo(404);
            for (String escape : List.of("../app.sqlite", "/app.sqlite", "db//app.sqlite", "./app.sqlite")) {
                HttpResponse<String> refused = put(adminKey, "data", escape, DATABASE);
                assertThat(refused.statusCode()).as("step 2: path '%s' is refused (%s)", escape, refused.body())
                    .isEqualTo(422);
                assertThat(codeOf(refused.body())).as("step 2: path '%s' by name", escape)
                    .isEqualTo("files_path_refused");
            }
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Files.MAX_FILE_KB, 1);
            HttpResponse<String> tooLarge = put(adminKey, "data", "app.sqlite", "x".repeat(2048));
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Files.MAX_FILE_KB, savedMaxFileKb);
            assertThat(tooLarge.statusCode()).as("step 2: over the cap (%s)", tooLarge.body()).isEqualTo(422);
            assertThat(codeOf(tooLarge.body())).isEqualTo("files_too_large");
            assertThat(helperDaemon.calls()).as("step 2: no refusal started a helper, nor removed one").isEmpty();

            // 3. A symlink inside the volume is refused by the walk, inside the helper, and nothing is written; the
            //    helper's start removed the app's own exited leftover first, and left the other app's alone.
            HttpResponse<String> linked = put(adminKey, "data", "link/passwd", DATABASE);
            assertThat(linked.statusCode()).as("step 3: through a symlink (%s)", linked.body()).isEqualTo(422);
            assertThat(codeOf(linked.body())).isEqualTo("files_path_refused");
            assertThat(helperDaemon.calls()).as("step 3: nothing was put, and the helper is gone")
                .noneMatch(call -> call.startsWith("put:"))
                .anyMatch(call -> call.startsWith("remove:") && !call.equals("remove:" + leftover));
            assertThat(helperDaemon.exitedNames()).as("step 3: the app's leftover went before the helper started")
                .containsExactly(othersLeftover);

            // 4. The put: through a helper binding ONLY the app's volume, at the path the release mounts it, owned
            //    like its directory by the daemon's extraction itself (no chown follows, 515867d2a), and the helper
            //    removed afterwards.
            HttpResponse<String> written = put(adminKey, "data", "app.sqlite", DATABASE);
            assertThat(written.statusCode()).as("step 4: written (%s)", written.body()).isEqualTo(200);
            assertThat(written.body()).as("step 4: the answer").contains("\"status\":\"written\"")
                .contains("\"bytes\":" + DATABASE.getBytes(StandardCharsets.UTF_8).length);
            assertThat(helperDaemon.text("/data/app.sqlite")).as("step 4: the file is in the volume")
                .isEqualTo(DATABASE);
            assertThat(helperDaemon.ownerOf("/data/app.sqlite")).as("step 4: owned like its directory")
                .isEqualTo(helperDaemon.ownerOf("/data"));
            assertThat(helperDaemon.calls()).as("step 4: its owner came with the extraction, no chown ran after it")
                .noneMatch(call -> call.startsWith("exec:chown"));
            Map<String, Object> create = helperDaemon.lastCreate();
            assertThat(create).as("step 4: a helper was created").isNotNull();
            assertThat(create.get("Image")).as("step 4: from the pinned image").isEqualTo(PinnedImages.ALPINE);
            Map<?, ?> hostConfig = (Map<?, ?>) create.get("HostConfig");
            assertThat(hostConfig.get("NetworkMode")).as("step 4: with no network").isEqualTo("none");
            assertThat(hostConfig.get("Mounts")).as("step 4: binding the app's volume and nothing else")
                .isEqualTo(List.of(Map.of("Type", "bind", "Source", hostPath, "Target", "/data")));
            List<String> calls = helperDaemon.calls();
            String helper = calls.stream().filter(call -> call.startsWith("create:")).reduce((a, b) -> b)
                .orElseThrow().substring("create:".length());
            assertThat(helper).as("step 4: named as a files helper of this app")
                .startsWith(ControllerScope.kindPrefix(ControllerScope.KIND_FILES) + appId + "-");
            assertThat(calls.get(calls.size() - 1)).as("step 4: and removed when the write was done")
                .isEqualTo("remove:" + helper);

            // 4a. The periodic instance reconciliation removes every exited helper, the other app's leftover too.
            VolumeHelper.removeExitedEverywhere();
            assertThat(helperDaemon.exitedNames()).as("step 4a: no exited helper is left").isEmpty();
            // 5. The first deploy is healthy and mounts that same directory at the same path.
            releaseDaemon.install();
            ApplicationReleases.converge(appId, Map.of());
            Row serving = ApplicationReleases.ownedServing(appId);
            assertThat(serving).as("step 5: the first release serves").isNotNull();
            Map<?, ?> mounted = (Map<?, ?>) InstanceModel.settingsOf(serving).get(ApplicationReleases.VOLUME_MOUNTS);
            assertThat(mounted.get(hostPath)).as("step 5: mounting the directory the file was put into")
                .isEqualTo("/data");
            assertThat(hosts.directoryOf(server, hostPath)).as("step 5: which is still the declared one")
                .isDirectory();
        } finally {
            ApplicationReleases.destroyFor(appId);
            FakeDockerDaemon.restore();
            releaseDaemon.close();
            FakeHostVolumes.uninstall();
        }
    }

    private HttpResponse<String> put(String key, String volume, String path, String body) throws Exception {
        return sendRequest(requestTo(putRoute(volume, path))
            .header("X-Api-Key", key)
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String putRoute(String volume, String path) {
        return "/api/v1/instances/" + appId + "/volumes/put?name=" + URLEncoder.encode(volume, StandardCharsets.UTF_8)
            + "&path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
    }
}
