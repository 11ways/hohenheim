package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.instance.InstanceExec;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.server.instance.WorkspaceUids;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workspace kind against a REAL Incus daemon: a runtime image converted and imported on
 * the host, a container whose workload runs as the derived uid, a home volume that is a real
 * btrfs subvolume with a real quota, an in-container checkout and build, a restart the data
 * survives, and the two delete verbs telling data from container.
 *
 * <p>What only this test can say: that the namespace-id-to-host-uid translation the
 * controller chowns by is the one the kernel actually enforces, that a file written inside
 * lands on the host owned by that mapped number, and that the image's
 * {@code hohenheim-init} really becomes PID 1. Every one of those is a kernel and daemon
 * behaviour the hermetic {@code WorkspaceKindTest} structurally cannot reach.</p>
 *
 * AIDEV-NOTE: it runs on the SECONDARY live host (nightstrom) so the Docker half of this
 * pair can hold daystrom without the two waves fighting over one machine's 3.9 GiB.
 *
 * AIDEV-NOTE: everything is carved under one uuid-named directory in the Incus pool and
 * removed in tearDown -- the pool's own {@code images/}, {@code containers/} and
 * {@code custom/} subvolumes are never touched.
 */
@Tag("slow") // live lane: needs a real Incus host; runs via `zenit-dev test --all`
class WorkspaceIncusLiveTest {

    /** Unique per live class: the authorized_keys sweep is keyed on this name. */
    private static final String HOST = "live-incus-workspace";

    /** The pool filesystem the throwaway data root is carved out of. */
    private static final String POOL = System.getProperty(
        "hohenheim.live.btrfs.path", "/var/lib/incus/storage-pools/default");

    /** A tiny public repository: the clone is the subject, its content is not. */
    private static final String REPOSITORY = "https://github.com/octocat/Hello-World.git";

    private static SqliteDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;
    private static String dataPath;
    private static String savedDataPath;
    private static HostShell shell;
    private static Integer instanceId;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.configuredSecondary();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no SECOND live incus host (url_b) enrolled at " + LiveIncusHost.CONFIG);

        File db = File.createTempFile("hohenheim-workspace-incus-live", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();

        Db.run(datasource, () -> enrolledFingerprint =
            remote.enrollThroughProduct(HOST, "hohenheim-live-workspace"));

        // The data root is this run's own directory inside the pool, so the volume backend
        // probe finds btrfs and every path this test makes is removable by one prefix.
        // The VOLUME ROOT, not the data path: the controller's own data path stays local
        // (it holds the per-host ssh identity store), and only the workload host's volume
        // directory moves onto the btrfs pool.
        dataPath = POOL + "/hohenheim-workspace-live-" + UUID.randomUUID();
        savedDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.VOLUME_ROOT);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_ROOT, dataPath);

        Db.run(datasource, () -> {
            Row server = Models.get(ServerModel.class).findByName(HOST);
            shell = HostShell.forServer(server);
            shell.run("mkdir -p " + HostShell.quote(dataPath));
            VolumeBackends.Detection detection = VolumeBackends.runAndStore(HOST);
            LiveLane.require(LiveLane.Need.INCUS_HOST,
                detection.backend() == VolumeBackend.BTRFS,
                "the live data root is not on btrfs: " + detection.detail());
        });
    }

    @AfterAll
    static void tearDown() {
        if (datasource != null && instanceId != null) {
            Db.run(datasource, () -> {
                try {
                    new InstanceService().destroyWithData(instanceId);
                } catch (RuntimeException alreadyGone) {
                    System.out.println("=== cleanup: instance -> " + alreadyGone.getMessage());
                }
            });
        }
        if (shell != null && dataPath != null) {
            // Subvolumes cannot be rmdir'd; the listing is scoped to this run's own
            // directory name, which is what keeps the pool's own subvolumes out of it.
            shell.run("btrfs subvolume list -o " + HostShell.quote(POOL)
                + " 2>/dev/null | awk '{print $NF}' | grep -F "
                + HostShell.quote(baseName()) + " | sort -r | while read -r p; do"
                + " btrfs subvolume delete " + HostShell.quote(POOL) + "/\"$p\" >/dev/null;"
                + " done");
            shell.run("rm -rf " + HostShell.quote(dataPath));
            shell.run("incus image delete " + HostShell.quote(incusAlias()) + " 2>/dev/null;"
                + " docker rmi " + HostShell.quote(dockerReference()) + " >/dev/null 2>&1;"
                + " true", 120);
        }
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_ROOT,
            savedDataPath);
        if (remote != null) {
            System.out.println("=== cleanup: shared objects -> "
                + remote.releaseControllerSharedObjects());
            System.out.println("=== cleanup: authorized_keys -> "
                + remote.releaseAuthorizedKeys());
        }
        if (remote != null && enrolledFingerprint != null) {
            try {
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    @Test
    void theWholeWorkspaceLifecycleAgainstTheRealIncusDaemon() {
        Db.run(datasource, () -> {

            // 1. A workspace record on the enrolled host, from a runtime image that does
            //    not exist on that host yet -- the import is part of what is under test.
            int imageId = runtimeImageRow();
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("repository_url", REPOSITORY);
            settings.put("branch", "master");
            settings.put("build_command", "echo built-inside > "
                + WorkspaceBuilds.CHECKOUT_PATH + "/BUILT");
            settings.put("memory_limit_mb", 512);
            settings.put("home_quota_mb", 256);
            instanceId = workspaceRecord(imageId, settings);
            int uid = WorkspaceUids.forInstance(instanceId);

            // 2. Deploy through the FUNNEL: image converted and imported, home volume
            //    carved, container up -- and, because this workspace names a repository,
            //    its source checked out and built inside it before the workload comes
            //    back. Pre-fix this produced a running container over an EMPTY /home/site.
            //    (WorkspaceBuilds had one caller outside its tests: the forge webhook.)
            InstanceStatus status = new InstanceService().deploy(instanceId);
            assertThat(status.state())
                .as("step 2: the workspace is running on the real daemon")
                .isEqualTo(ContainerState.RUNNING);
            assertThat(shell.run("incus image info " + HostShell.quote(incusAlias())).ok())
                .as("step 2: the runtime image was imported into the host's Incus store")
                .isTrue();

            // 3. The home volume is a real btrfs subvolume with a real qgroup limit.
            String home = InstanceVolumes.hostPathFor(instanceId, WorkspaceKind.HOME_VOLUME);
            assertThat(shell.run("btrfs subvolume show " + HostShell.quote(home)).ok())
                .as("step 3: /home/site is a btrfs subvolume, not a directory").isTrue();
            assertThat(shell.run("btrfs qgroup show -f -re --raw " + HostShell.quote(home)).text())
                .as("step 3: carrying the declared quota")
                .contains(String.valueOf(256L * 1024L * 1024L));
            int hostUid = WorkspaceUids.incusHostUid(uid,
                WorkspaceUids.incusSubuidBase(HOST, shell));
            assertThat(shell.run("stat -c %u " + HostShell.quote(home)).text().trim())
                .as("step 3: and owned by the HOST uid the workspace's namespace id maps"
                    + " to -- the translation is the whole reason a workspace can write"
                    + " into its own home on this runtime")
                .isEqualTo(String.valueOf(hostUid));

            // 4. The identity really landed: the workload runs as the namespace id.
            assertThat(shell.run("incus exec " + handle()
                    + " -- ps -o user= -C sleep").text())
                .as("step 4: the workload process inside the container is the workspace uid")
                .contains(String.valueOf(uid));
            InstanceExec.Run identity = new InstanceExec().run(instanceId, "id -u");
            assertThat(identity.output().trim())
                .as("step 4: and an exec through the product lane lands as the same uid")
                .isEqualTo(String.valueOf(uid));

            // 5. A file written INSIDE is visible on the host path, owned by that uid --
            //    the property the files tab, a host backup and the container all rely on.
            new InstanceExec().run(instanceId,
                "/bin/sh -c 'echo from-inside > " + WorkspaceKind.HOME_PATH + "/marker.txt'");
            assertThat(shell.run("cat " + HostShell.quote(home + "/marker.txt")).text())
                .as("step 5: the host reads what the container wrote")
                .isEqualTo("from-inside");
            assertThat(shell.run("stat -c %u " + HostShell.quote(home + "/marker.txt"))
                    .text().trim())
                .as("step 5: owned on the host by the mapped uid, not by root")
                .isEqualTo(String.valueOf(hostUid));

            // 6. Checkout and build INSIDE the container, as that uid.
            WorkspaceBuilds.Outcome deployed = new WorkspaceBuilds()
                .deploy(instanceId, "master", "live test");
            assertThat(deployed.commitSha())
                .as("step 6: the checkout reports a commit").hasSize(40);
            assertThat(deployed.built())
                .as("step 6: and the build command ran").isTrue();
            assertThat(shell.run("cat " + HostShell.quote(home + "/app/BUILT")).text())
                .as("step 6: the build's output is in the volume")
                .isEqualTo("built-inside");
            assertThat(shell.run("stat -c %u " + HostShell.quote(home + "/app/README")).text()
                    .trim())
                .as("step 6: and the checkout is owned by the workspace, not by root")
                .isEqualTo(String.valueOf(hostUid));

            // 7. THE credential invariant, on the real volume: nothing in the checkout
            //    carries an Authorization header or a URL credential.
            assertThat(shell.run("grep -rIl -e 'Authorization: Basic' -e '://[^/]*:[^/]*@' "
                    + HostShell.quote(home) + " 2>/dev/null | head").text().trim())
                .as("step 7: no file in the volume carries a credential after a clone")
                .isEmpty();
            // Read the FILE, not `git config`: the repository belongs to the workspace uid
            // and git on the host refuses a dubious-ownership repo -- and the file is what
            // the invariant is about anyway.
            assertThat(shell.run("grep -m1 'url = ' "
                    + HostShell.quote(home + "/app/.git/config")).text().trim())
                .as("step 7: and the stored remote is the clean URL")
                .isEqualTo("url = " + REPOSITORY);

            // 8. A restart is a new container over the SAME data.
            new InstanceService().restart(instanceId);
            assertThat(shell.run("cat " + HostShell.quote(home + "/marker.txt")).text())
                .as("step 8: the file written before the restart survived it")
                .isEqualTo("from-inside");

            // 9. An ordinary destroy takes the container and KEEPS the data.
            new InstanceService().destroy(instanceId);
            assertThat(shell.run("incus info " + handle() + " >/dev/null 2>&1").ok())
                .as("step 9: the container is gone from the daemon").isFalse();
            assertThat(shell.run("test -f " + HostShell.quote(home + "/marker.txt")).ok())
                .as("step 9: and the volume still holds what it held").isTrue();

            // 10. Only the explicit delete-with-data removes the bytes.
            new InstanceService().destroyWithData(instanceId);
            assertThat(shell.run("test -e " + HostShell.quote(home)).ok())
                .as("step 10: the volume directory is gone").isFalse();
            assertThat(InstanceVolumes.declaredFor(instanceId))
                .as("step 10: and so is its declaration").isEmpty();
            instanceId = null;
        });
    }

    // -- fixtures ---------------------------------------------------------------

    /** The daemon-side name: controller-scoped, exactly as the driver mints it. */
    private static String handle() {
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
    }

    private static String baseName() {
        return dataPath.substring(dataPath.lastIndexOf('/') + 1);
    }

    /** Test-scoped image references, so a run never collides with a real deployment's. */
    private static String dockerReference() {
        return "hohenheim/livetest-debian-13:1";
    }

    private static String incusAlias() {
        return "hohenheim/livetest-debian-13";
    }

    private static int runtimeImageRow() {
        RuntimeImageModel model = Models.get(RuntimeImageModel.class);
        Row row = model.createEmptyRow();
        row.set(RuntimeImageModel.NAME, "livetest-debian-13");
        row.set(RuntimeImageModel.DOCKER_IMAGE, dockerReference());
        row.set(RuntimeImageModel.INCUS_IMAGE, incusAlias());
        row.set(RuntimeImageModel.BUILD_CONTEXT, "images/debian-13");
        row.set(RuntimeImageModel.DEFAULT_COMMAND, null);
        row.set(RuntimeImageModel.ENABLED, true);
        model.save(row);
        return row.get(RuntimeImageModel.ID);
    }

    private static int workspaceRecord(int imageId, Map<String, Object> settings) {
        Row host = Models.get(ServerModel.class).findByName(HOST);
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "live-workspace");
        row.set(InstanceModel.KIND, WorkspaceKind.ID.toString());
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, host.get(ServerModel.ID));
        row.set(InstanceModel.RUNTIME_IMAGE_ID, imageId);
        Models.get(InstanceModel.class).save(row);
        // No volume is declared here on purpose: the deploy's own preparation declares the
        // home from the settings, and a hand-written row would prove the fixture instead.
        return row.get(InstanceModel.ID);
    }
}
