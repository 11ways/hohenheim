package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceExec;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.server.instance.WorkspaceUids;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workspace kind against a REAL remote Docker daemon: the runtime image BUILT on that
 * host from its packaged context, a container running as the derived uid, a home volume
 * that is a real btrfs subvolume with a real quota, an in-container checkout and build, a
 * restart the data survives, and the two delete verbs telling data from container.
 *
 * The twin of {@code WorkspaceIncusLiveTest}: the same declarations, the other runtime.
 * What only this half can say is that Docker's numeric {@code User} field really produces
 * host-side ownership by that number through a bind mount, and that the sandboxed build
 * lane really produces the image the deploy then starts.
 *
 * AIDEV-NOTE: it runs on the PRIMARY live host (daystrom); the Incus half holds the
 * secondary, so neither run contends for one machine's 3.9 GiB.
 *
 * AIDEV-NOTE: the host is enrolled through {@link LiveIncusHost}'s real ssh ceremony (key
 * minted by the product, installed out of band, scanned fingerprint compared against what
 * the host reports for itself) and then DECLARED a docker host. Only the runtime
 * declaration is a fixture shortcut, and it is the same field an operator sets in the host
 * form; the ssh trust the Docker transport rides is the product's own.
 */
@Tag("slow") // live lane: needs a real remote Docker daemon; runs via `zenit-dev test --all`
class WorkspaceDockerLiveTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    /** Unique per live class: the authorized_keys sweep is keyed on this name. */
    private static final String HOST = "live-docker-workspace";

    /** The btrfs filesystem the throwaway data root is carved out of. */
    private static final String POOL = System.getProperty(
        "hohenheim.live.btrfs.path", "/var/lib/incus/storage-pools/default");

    /** A tiny public repository: the clone is the subject, its content is not. */
    private static final String REPOSITORY = "https://github.com/octocat/Hello-World.git";

    private static SqlDatasource datasource;
    private static LiveIncusHost remote;
    private static String enrolledFingerprint;
    private static String dataPath;
    private static String savedDataPath;
    private static HostShell shell;
    private static Integer instanceId;
    private static Boolean savedNftables;
    private static final List<Integer> travelled = new ArrayList<>();
    private static Path targetDir;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.requireSshLane();

        datasource = APP.database();

        Db.run(datasource, () -> {
            // The real ssh ceremony; the daemon-trust half is not needed for a docker host.
            remote.enrol(HOST);
            remote.enrollSshLaneThroughProduct(HOST);
            Row host = Models.get(ServerModel.class).findByName(HOST);
            host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
            host.set(ServerModel.MODE, ServerModel.MODE_SSH);
            host.set(ServerModel.INCUS_URL, null);
            Models.get(ServerModel.class).save(host);
            remote.admitThroughProduct(HOST);
        });

        // Per-workload network policy must be ENFORCEABLE for the sandboxed build lane to
        // start anything at all, and this host's ssh lane is what applies the nft rules.
        savedNftables = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Security.NFTABLES_ENABLED);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.NFTABLES_ENABLED, true);

        // The VOLUME ROOT, not the data path: the controller's own data path stays local
        // (it holds the per-host ssh identity store), and only the workload host's volume
        // directory moves onto the btrfs pool.
        dataPath = POOL + "/hohenheim-workspace-docker-live-" + UUID.randomUUID();
        savedDataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.VOLUME_ROOT);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.VOLUME_ROOT, dataPath);

        Db.run(datasource, () -> {
            Row server = Models.get(ServerModel.class).findByName(HOST);
            shell = HostShell.forServer(server);
            shell.run("mkdir -p " + HostShell.quote(dataPath));
            VolumeBackends.Detection detection = VolumeBackends.runAndStore(HOST);
            LiveLane.require(LiveLane.Need.REMOTE_HOST,
                detection.backend() == VolumeBackend.BTRFS,
                "the live data root is not on btrfs: " + detection.detail());
        });
    }

    @AfterAll
    static void tearDown() {
        if (datasource != null) {
            Db.run(datasource, () -> {
                List<Integer> leftovers = new ArrayList<>(travelled);
                if (instanceId != null) {
                    leftovers.add(instanceId);
                }
                for (Integer leftover : leftovers) {
                    try {
                        new InstanceService().destroyWithData(leftover);
                    } catch (RuntimeException alreadyGone) {
                        System.out.println("=== cleanup: instance " + leftover + " -> " + alreadyGone.getMessage());
                    }
                }
            });
        }
        if (targetDir != null) {
            deleteRecursively(targetDir);
        }
        if (shell != null && dataPath != null) {
            shell.run("btrfs subvolume list -o " + HostShell.quote(POOL)
                + " 2>/dev/null | awk '{print $NF}' | grep -F "
                + HostShell.quote(baseName()) + " | sort -r | while read -r p; do"
                + " btrfs subvolume delete " + HostShell.quote(POOL) + "/\"$p\" >/dev/null;"
                + " done");
            shell.run("rm -rf " + HostShell.quote(dataPath));
            shell.run("docker rmi " + HostShell.quote(dockerReference())
                + " >/dev/null 2>&1; true", 120);
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.VOLUME_ROOT,
            savedDataPath);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.NFTABLES_ENABLED,
            savedNftables);
        if (remote != null) {
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

    /**
     * A workspace's home rides its plain backup on the real Docker host: the restored record's home holds the marker
     * written inside the source, owned by the NEW record's uid, and the source keeps its own copy.
     */
    @Test
    void aWorkspaceHomeRidesItsBackupToANewRecord() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int hostId = Models.get(ServerModel.class).findByName(HOST).get(ServerModel.ID);

            // 1. A workspace runs and a marker is written into its home from inside.
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("memory_limit_mb", 512);
            settings.put("home_quota_mb", 256);
            int sourceId = workspaceRecord(runtimeImageRow(), settings, "live-docker-travel", hostId);
            travelled.add(sourceId);
            assertThat(service.deploy(sourceId).state())
                .as("step 1: the workspace runs").isEqualTo(ContainerState.RUNNING);
            String marker = "HHHOME-" + UUID.randomUUID();
            new InstanceExec().run(sourceId,
                "/bin/sh -c 'echo " + marker + " > " + WorkspaceKind.HOME_PATH + "/marker.txt'");
            String sourceHome = InstanceVolumes.hostPathFor(sourceId, WorkspaceKind.HOME_VOLUME);
            assertThat(shell.run("cat " + HostShell.quote(sourceHome + "/marker.txt")).text())
                .as("step 1: the home holds the marker").isEqualTo(marker);

            // 2. A plain backup of the running workspace names its home.
            targetDir = createTempDirectory();
            int backupId = offHostBackup(sourceId, targetDir);
            Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
            assertThat(List.<Object>copyOf(RawValues.list(RawValues.map((Object) backup.get(InstanceBackupModel.SUMMARY))
                    .get(InstanceBackupModel.SUMMARY_HOST_VOLUMES))))
                .as("step 2: the backup carries the home").containsExactly(WorkspaceKind.HOME_VOLUME);

            // 3. Restored as a new record, its home holds the marker owned by the new record's uid, and the restored
            //    workspace reads it from inside; the source keeps its own copy.
            int restoredId = backups.restoreToNew(backupId, "live-docker-restored", HOST).instanceId();
            travelled.add(restoredId);
            String restoredHome = InstanceVolumes.hostPathFor(restoredId, WorkspaceKind.HOME_VOLUME);
            assertThat(shell.run("cat " + HostShell.quote(restoredHome + "/marker.txt")).text())
                .as("step 3: the restored home holds the marker").isEqualTo(marker);
            assertThat(shell.run("stat -c %u " + HostShell.quote(restoredHome + "/marker.txt")).text().trim())
                .as("step 3: owned by the new record's uid")
                .isEqualTo(String.valueOf(WorkspaceUids.forInstance(restoredId)));
            assertThat(new InstanceExec().run(restoredId, "cat " + WorkspaceKind.HOME_PATH + "/marker.txt")
                    .output().trim())
                .as("step 3: the restored workspace reads it from inside").isEqualTo(marker);
            assertThat(shell.run("cat " + HostShell.quote(sourceHome + "/marker.txt")).text())
                .as("step 3: the source keeps its own copy").isEqualTo(marker);

            // 4. Delete-with-data removes both homes, and the backup goes with its artifact.
            service.destroyWithData(restoredId);
            service.destroyWithData(sourceId);
            travelled.clear();
            assertThat(shell.run("test -e " + HostShell.quote(restoredHome)).ok())
                .as("step 4: the restored home is gone").isFalse();
            backups.delete(backupId);
        });
    }

    @Test
    void theWholeWorkspaceLifecycleAgainstTheRealDockerDaemon() {
        Db.run(datasource, () -> {

            // 1. A workspace record on the enrolled host, from a runtime image the host
            //    does not have yet -- building it is part of what is under test.
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

            // 2. Deploy through the FUNNEL: image built on the host, home volume carved,
            //    container up -- and, because this workspace names a repository, its source
            //    checked out and built inside it before the workload comes back. Pre-fix
            //    this produced a running container over an EMPTY /home/site.
            //    (WorkspaceBuilds had one caller outside its tests: the forge webhook.)
            InstanceStatus status = new InstanceService().deploy(instanceId);
            assertThat(status.state())
                .as("step 2: the workspace is running on the real daemon")
                .isEqualTo(ContainerState.RUNNING);
            assertThat(shell.run("docker image inspect "
                    + HostShell.quote(dockerReference()) + " >/dev/null 2>&1").ok())
                .as("step 2: and its runtime image was built on that host")
                .isTrue();
            assertThat(shell.run("docker inspect -f '{{.Config.User}}' " + handle()).text()
                    .trim())
                .as("step 2: the container declares the derived uid as its user")
                .isEqualTo(String.valueOf(uid));

            // 3. The home volume is a real btrfs subvolume with a real qgroup limit.
            String home = InstanceVolumes.hostPathFor(instanceId, WorkspaceKind.HOME_VOLUME);
            assertThat(shell.run("btrfs subvolume show " + HostShell.quote(home)).ok())
                .as("step 3: /home/site is a btrfs subvolume, not a directory").isTrue();
            assertThat(shell.run("btrfs qgroup show -f -re --raw " + HostShell.quote(home)).text())
                .as("step 3: carrying the declared quota")
                .contains(String.valueOf(256L * 1024L * 1024L));
            assertThat(shell.run("stat -c %u " + HostShell.quote(home)).text().trim())
                .as("step 3: and owned by the workspace's derived uid")
                .isEqualTo(String.valueOf(uid));

            // 4. An exec through the product lane lands as that same number.
            InstanceExec.Run identity = new InstanceExec().run(instanceId, "id -u");
            assertThat(identity.output().trim())
                .as("step 4: the shell lane runs as the workspace uid")
                .isEqualTo(String.valueOf(uid));

            // 5. A file written INSIDE is visible on the host path, owned by that uid.
            new InstanceExec().run(instanceId,
                "/bin/sh -c 'echo from-inside > " + WorkspaceKind.HOME_PATH + "/marker.txt'");
            assertThat(shell.run("cat " + HostShell.quote(home + "/marker.txt")).text())
                .as("step 5: the host reads what the container wrote")
                .isEqualTo("from-inside");
            assertThat(shell.run("stat -c %u " + HostShell.quote(home + "/marker.txt"))
                    .text().trim())
                .as("step 5: owned by the same number on both sides")
                .isEqualTo(String.valueOf(uid));

            // 6. Checkout and build INSIDE the container, as that uid -- and the durable
            //    build_operations row the Deploys tab reads, with the captured log on it.
            //    Step 2's deploy already ran this lane (the funnel folds a source-declared
            //    workspace into WorkspaceBuilds); this proves the verb directly and proves
            //    a re-deploy over an existing checkout FETCHES rather than re-cloning.
            WorkspaceBuilds.Outcome deployed = new WorkspaceBuilds()
                .deploy(instanceId, "master", null, DeployTrigger.MANUAL);
            Row recorded = Models.get(BuildOperationModel.class)
                .findById(deployed.operationId());
            assertThat((String) recorded.get(BuildOperationModel.STATUS))
                .as("step 6: the deploy is recorded as a durable operation")
                .isEqualTo(BuildOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
            assertThat((String) recorded.get(BuildOperationModel.BUILDER_KIND))
                .as("step 6: under the workspace builder kind, never a sandboxed one")
                .isEqualTo(BuildOperationModel.KIND_WORKSPACE);
            assertThat((String) recorded.get(BuildOperationModel.LOG))
                .as("step 6: carrying what the checkout and the build printed")
                .isNotBlank();
            assertThat((String) recorded.get(BuildOperationModel.IMAGE_ID))
                .as("step 6: and NO artifact identity -- a workspace build promotes nothing")
                .isNull();
            assertThat(deployed.commitSha())
                .as("step 6: the checkout reports a commit").hasSize(40);
            assertThat(deployed.built())
                .as("step 6: and the build command ran").isTrue();
            assertThat(shell.run("cat " + HostShell.quote(home + "/app/BUILT")).text())
                .as("step 6: the build's output is in the volume")
                .isEqualTo("built-inside");
            assertThat(shell.run("stat -c %u " + HostShell.quote(home + "/app/README"))
                    .text().trim())
                .as("step 6: and the checkout is owned by the workspace, not by root")
                .isEqualTo(String.valueOf(uid));

            // 7. THE credential invariant, on the real volume.
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
            assertThat(shell.run("docker inspect " + handle() + " >/dev/null 2>&1").ok())
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

    /** A test-scoped image reference, so a run never collides with a real deployment's. */
    private static String dockerReference() {
        return "hohenheim/livetest-ws-debian-13:1";
    }

    /** The test's runtime image row, made once per class database (both journeys use it). */
    private static int runtimeImageRow() {
        RuntimeImageModel model = Models.get(RuntimeImageModel.class);
        Row existing = model.find().where(RuntimeImageModel.NAME.eq("livetest-ws-debian-13")).first();
        if (existing != null) {
            return existing.get(RuntimeImageModel.ID);
        }
        Row row = model.createEmptyRow();
        row.set(RuntimeImageModel.NAME, "livetest-ws-debian-13");
        row.set(RuntimeImageModel.DOCKER_IMAGE, dockerReference());
        row.set(RuntimeImageModel.INCUS_IMAGE, null);
        row.set(RuntimeImageModel.BUILD_CONTEXT, "images/debian-13");
        row.set(RuntimeImageModel.DEFAULT_COMMAND, null);
        row.set(RuntimeImageModel.ENABLED, true);
        model.save(row);
        return row.get(RuntimeImageModel.ID);
    }

    private static int workspaceRecord(int imageId, Map<String, Object> settings) {
        return workspaceRecord(imageId, settings, "live-workspace-docker",
            Models.get(ServerModel.class).findByName(HOST).get(ServerModel.ID));
    }

    private static int workspaceRecord(int imageId, Map<String, Object> settings, String name, int hostId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, WorkspaceKind.ID.toString());
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, hostId);
        row.set(InstanceModel.RUNTIME_IMAGE_ID, imageId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** A filesystem backup target on the controller + one COMPLETE backup of the instance. */
    private static int offHostBackup(int ownerId, Path directory) {
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "workspace-docker-live-target");
        target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        target.set(BackupTargetModel.SETTINGS, new LinkedHashMap<>(Map.of("path", directory.toString())));
        Models.get(BackupTargetModel.class).save(target);
        Row instance = Models.get(InstanceModel.class).findById(ownerId);
        instance.set(InstanceModel.BACKUP_TARGET_ID, target.get(BackupTargetModel.ID));
        Models.get(InstanceModel.class).save(instance);
        return new InstanceBackups().backupNow(ownerId);
    }

    private static Path createTempDirectory() {
        try {
            return Files.createTempDirectory("workspace-docker-backup-target");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // best effort
        }
    }
}
