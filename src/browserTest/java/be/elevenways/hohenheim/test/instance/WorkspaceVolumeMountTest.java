package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.files.InstanceFiles;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What a workspace's DECLARED volumes actually mount, through the production deploy verb.
 *
 * AIDEV-NOTE: the defect, found 2026-08-23. {@code WorkspaceKind.specFor} hardcoded its
 * bind map to the single {@code home} entry while {@code prepareForDeploy} created, quota'd
 * and chowned EVERY declared volume and discarded the map it got back. An operator could
 * declare a volume on a workspace, watch the Volumes tab report its usage against the host
 * directory, and have the container write to its ephemeral rootfs at that path -- lost on
 * the next deploy, with the UI still showing a quota'd volume. The application tier never
 * had it: its releases carry {@code mountsFor}'s own result.
 *
 * <p>The live halves ({@code WorkspaceDockerLiveTest}, {@code WorkspaceIncusLiveTest})
 * prove the same mounts against real daemons; neither half replaces the other.</p>
 */
class WorkspaceVolumeMountTest {

    private static final String KIND = WorkspaceKind.ID.toString();
    private static final String DATA_PATH = "/srv/hoh-vol";

    private static SqlDatasource datasource;
    private static int hostId;
    private static FakeWorkspaceDaemon daemon;
    private static String savedDataPath;
    private static Integer savedUidBase;

    @BeforeAll
    static void setUp() throws Exception {
        BackupLaneFixture fixture = BackupLaneFixture.install();
        datasource = fixture.datasource;
        hostId = fixture.hostId;
        savedDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        savedUidBase = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Storage.VOLUME_UID_BASE);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE, 200000);
        daemon = FakeWorkspaceDaemon.install();
    }

    @AfterAll
    static void tearDown() {
        FakeWorkspaceDaemon.uninstall();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, savedDataPath);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE,
            savedUidBase);
        BackupLaneFixture.uninstall();
    }

    /**
     * A volume declared on a workspace is mounted by the container it deploys, stays
     * mounted across a redeploy, and shows up as a root the Files tab can browse.
     */
    @Test
    void aDeclaredVolumeIsMountedByTheWorkloadAndBrowsableAndSurvivesARedeploy() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            int id = workspace("ws-volume-mount");
            String home = DATA_PATH + "/volumes/" + id + "/home";
            String data = DATA_PATH + "/volumes/" + id + "/data";

            // 1. The operator declares a second volume, exactly as the Volumes tab does:
            //    a name, a container path and a quota. Nothing else is configured.
            InstanceVolumes.declare(id, "data", "/data", 256L * 1024L * 1024L, false);

            // 2. THE DEFECT. Deploying used to hand the daemon a bind map with the home
            //    entry and nothing else, so /data inside the container was ephemeral
            //    rootfs while the tab reported a quota'd host directory behind it.
            service.deploy(id, DeployTrigger.MANUAL);
            assertThat(daemon.lastCreated().binds())
                .as("step 2: the container mounts the declared data volume beside its home")
                .containsEntry(data, "/data")
                .containsEntry(home, WorkspaceKind.HOME_PATH)
                .hasSize(2);

            // 3. The host preparation that CREATES, quotas and chowns those directories
            //    runs before the container that binds them: a bind whose source is missing
            //    is created root-owned by the daemon, which the workspace cannot write to.
            assertThat(daemon.events)
                .as("step 3: every create is preceded by the host preparation")
                .containsSubsequence("prepare:" + id, "create:" + handleOf(id));

            // 4. And the Files tab follows with nothing wired at its call site: its roots
            //    are the spec's mounts, so the new volume is browsable the moment it is
            //    mounted.
            assertThat(new InstanceFiles(service).volumeRoots(id))
                .as("step 4: both mounts are file-browser roots")
                .contains("/data", WorkspaceKind.HOME_PATH);

            // 5. A REDEPLOY keeps both. The home volume is a real declared row by now
            //    (prepareForDeploy declares it on the first deploy), which is the case in
            //    which the kind's guaranteed home entry could have become a second mount.
            WorkspaceKind.ensureHomeDeclared(id, Map.of());
            assertThat(InstanceVolumes.declaredFor(id))
                .as("step 5: two declarations now, home included")
                .hasSize(2);
            service.deploy(id, DeployTrigger.MANUAL);
            assertThat(daemon.lastCreated().binds())
                .as("step 5: the redeployed container mounts the same two directories")
                .containsExactlyInAnyOrderEntriesOf(
                    Map.of(home, WorkspaceKind.HOME_PATH, data, "/data"));
            assertThat(daemon.events)
                .as("step 5: and the redeploy re-prepared the host before recreating it,"
                    + " so a directory removed between deploys is back before the bind")
                .containsSubsequence("prepare:" + id, "create:" + handleOf(id),
                    "prepare:" + id, "create:" + handleOf(id));

            // 6. The INCUS lane comes free because it derives its disk devices from the
            //    same spec.binds(). The spec asserted above is fed to the real driver over
            //    a fake daemon: two disk devices, each with its own host source and path.
            assertThat(incusDisksOf(daemon.lastCreated()))
                .as("step 6: the Incus driver mounts both directories as disk devices")
                .containsEntry(data, "/data")
                .containsEntry(home, WorkspaceKind.HOME_PATH);

            // 7. FALSIFIED: a workspace that declares nothing extra still mounts exactly
            //    its home, so step 2 is the declaration reaching the daemon and not a
            //    driver that mounts whatever it finds.
            int bare = workspace("ws-volume-bare");
            service.deploy(bare, DeployTrigger.MANUAL);
            assertThat(daemon.lastCreated().binds())
                .as("step 7: an undeclared workspace mounts its home and nothing else")
                .containsExactly(Map.entry(DATA_PATH + "/volumes/" + bare + "/home",
                    WorkspaceKind.HOME_PATH));
        });
    }

    /**
     * Two volumes at one container path refuse BY NAME, rather than handing the daemon two
     * directories for one path.
     */
    @Test
    void twoVolumesDeclaringOneContainerPathRefuseByName() {
        Db.run(datasource, () -> {
            int id = workspace("ws-volume-clash");

            // 1. Two declarations, one path. Harmless while the second was never mounted;
            //    a broken container now that every declaration reaches the spec.
            InstanceVolumes.declare(id, "data", "/data", null, false);
            InstanceVolumes.declare(id, "cache", "/data", null, false);

            Throwable refused = catchThrowable(() ->
                InstanceKinds.getHandler(KIND).specFor(id, Map.of()));
            assertThat(refused)
                .as("step 1: the derivation refuses the collision")
                .isInstanceOf(Violations.class)
                .as("step 1: naming the path and BOTH volumes, so it is actionable")
                .hasMessageContaining("volume_container_path_conflict");
            assertThat(String.valueOf(refused.getMessage()))
                .as("step 1: the offending volumes are named")
                .contains("data")
                .contains("cache");

            // 2. And the workspace's own home is not a special case: a declaration that
            //    claims HOME_PATH collides with the mount the kind guarantees.
            int shadowed = workspace("ws-volume-shadow");
            InstanceVolumes.declare(shadowed, "shadow", WorkspaceKind.HOME_PATH, null, false);
            assertThat(catchThrowable(() ->
                    InstanceKinds.getHandler(KIND).specFor(shadowed, Map.of())))
                .as("step 2: a volume claiming the home path refuses as well")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("volume_container_path_conflict");

            // 3. FALSIFIED: re-declare the clashing volume at a path of its own and the
            //    same three mounts derive, so the refusal is about the collision and not
            //    about declaring more than one volume.
            InstanceVolumes.declare(id, "cache", "/cache", null, false);
            assertThat(InstanceKinds.getHandler(KIND).specFor(id, Map.of()).binds())
                .as("step 3: three distinct paths mount three directories")
                .hasSize(3);
        });
    }

    // -- fixtures ---------------------------------------------------------------

    /** The disk devices the real Incus driver derives from a spec, over a fake daemon. */
    private static Map<String, String> incusDisksOf(InstanceSpec spec) {
        FakeIncusTransport incus = new FakeIncusTransport();
        incus.imageAliases.put("hohenheim/node-22", "fp-node-22");
        try {
            new IncusInstanceRuntime(new IncusClient(incus), Egress.OPEN,
                IncusWorkloadType.CONTAINER, null).create(spec);
        } catch (IOException refused) {
            throw new IllegalStateException("the fake incus daemon refused the create",
                refused);
        }
        return diskSourcesOf(incus);
    }

    /** The disk devices of the last Incus create body, as host source -&gt; container path. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> diskSourcesOf(FakeIncusTransport incus) {
        Map<String, String> sources = new LinkedHashMap<>();
        Object devices = incus.lastCreateBody.get("devices");
        if (devices instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof Map<?, ?> device && device.get("source") != null) {
                    sources.put(String.valueOf(device.get("source")),
                        String.valueOf(device.get("path")));
                }
            }
        }
        return sources;
    }

    private static String handleOf(int instanceId) {
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
    }

    /** A bare workspace on the fixture's host; no repository, so its deploy is the plain
     * workload half. */
    private static int workspace(String name) {
        RuntimeImageModel images = Models.get(RuntimeImageModel.class);
        Row image = images.findByName("volume-mount-image");
        if (image == null) {
            image = images.createEmptyRow();
            image.set(RuntimeImageModel.NAME, "volume-mount-image");
            image.set(RuntimeImageModel.INCUS_IMAGE, "hohenheim/node-22");
            image.set(RuntimeImageModel.DOCKER_IMAGE, "hohenheim/node-22:1");
            image.set(RuntimeImageModel.DEFAULT_COMMAND, "npm start");
            image.set(RuntimeImageModel.BUILD_CONTEXT, "images/node-22");
            image.set(RuntimeImageModel.ENABLED, true);
            images.save(image);
        }
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, KIND);
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<String, Object>());
        row.set(InstanceModel.SERVER_ID, hostId);
        row.set(InstanceModel.RUNTIME_IMAGE_ID, image.get(RuntimeImageModel.ID));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
