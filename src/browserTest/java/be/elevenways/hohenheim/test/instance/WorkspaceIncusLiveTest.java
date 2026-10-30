package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceExec;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
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
 * The workspace kind against a REAL Incus daemon: a runtime image converted and imported on
 * the host, a container whose workload runs as the derived uid, a home volume that is a real
 * btrfs subvolume with a real quota, an in-container checkout and build, a restart the data
 * survives, and the two delete verbs telling data from container.
 *
 * What only this test can say: that the namespace-id-to-host-uid translation the
 * controller chowns by is the one the kernel actually enforces, that a file written inside
 * lands on the host owned by that mapped number, and that the image's
 * {@code hohenheim-init} really becomes PID 1. Every one of those is a kernel and daemon
 * behaviour the hermetic {@code WorkspaceKindTest} structurally cannot reach.
 *
 * AIDEV-NOTE: the lifecycle runs on the SECONDARY live host (nightstrom) so the Docker half of this
 * pair can hold daystrom without the two runs fighting over one machine's 3.9 GiB. The travel journey
 * enrolls the PRIMARY (daystrom) as well: a home backed up and moved there lands on nightstrom.
 *
 * AIDEV-NOTE: everything is carved under one uuid-named directory in the Incus pool of each host and
 * removed in tearDown -- the pool's own {@code images/}, {@code containers/} and
 * {@code custom/} subvolumes are never touched.
 */
@Tag("slow") // live lane: needs a real Incus host; runs via `zenit-dev test --all`
class WorkspaceIncusLiveTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    /** Unique per live class: the authorized_keys sweep is keyed on this name. */
    private static final String HOST = "live-incus-workspace";

    /** The primary host the travel journey starts on. */
    private static final String HOST_A = "live-incus-workspace-a";

    /** The pool filesystem the throwaway data root is carved out of. */
    private static final String POOL = System.getProperty(
        "hohenheim.live.btrfs.path", "/var/lib/incus/storage-pools/default");

    /** A tiny public repository: the clone is the subject, its content is not. */
    private static final String REPOSITORY = "https://github.com/octocat/Hello-World.git";

    private static SqlDatasource datasource;
    private static LiveIncusHost remote;
    private static LiveIncusHost remoteA;
    private static String enrolledFingerprint;
    private static String enrolledFingerprintA;
    private static String dataPath;
    private static String savedDataPath;
    private static HostShell shell;
    private static HostShell shellA;
    private static Integer instanceId;
    private static final List<Integer> travelled = new ArrayList<>();
    private static Path targetDir;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveIncusHost.requireSecondary();
        remoteA = LiveIncusHost.requirePrimary();

        datasource = APP.database();

        Db.run(datasource, () -> {
            enrolledFingerprint = remote.enrollThroughProduct(HOST, "hohenheim-live-workspace");
            enrolledFingerprintA = remoteA.enrollThroughProduct(HOST_A, "hohenheim-live-workspace-a");
        });

        // The data root is this run's own directory inside the pool, so the volume backend
        // probe finds btrfs and every path this test makes is removable by one prefix.
        // The VOLUME ROOT, not the data path: the controller's own data path stays local
        // (it holds the per-host ssh identity store), and only the workload host's volume
        // directory moves onto the btrfs pool.
        dataPath = POOL + "/hohenheim-workspace-live-" + UUID.randomUUID();
        savedDataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.VOLUME_ROOT);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.VOLUME_ROOT, dataPath);

        Db.run(datasource, () -> {
            shell = HostShell.forServer(Models.get(ServerModel.class).findByName(HOST));
            shellA = HostShell.forServer(Models.get(ServerModel.class).findByName(HOST_A));
            for (String host : List.of(HOST, HOST_A)) {
                HostShell hostShell = HOST.equals(host) ? shell : shellA;
                hostShell.run("mkdir -p " + HostShell.quote(dataPath));
                VolumeBackends.Detection detection = VolumeBackends.runAndStore(host);
                LiveLane.require(LiveLane.Need.INCUS_HOST,
                    detection.backend() == VolumeBackend.BTRFS,
                    "the live data root on " + host + " is not on btrfs: " + detection.detail());
            }
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
        for (HostShell hostShell : new HostShell[] {shell, shellA}) {
            if (hostShell == null || dataPath == null) {
                continue;
            }
            // Subvolumes cannot be rmdir'd; the listing is scoped to this run's own
            // directory name, which is what keeps the pool's own subvolumes out of it.
            hostShell.run("btrfs subvolume list -o " + HostShell.quote(POOL)
                + " 2>/dev/null | awk '{print $NF}' | grep -F "
                + HostShell.quote(baseName()) + " | sort -r | while read -r p; do"
                + " btrfs subvolume delete " + HostShell.quote(POOL) + "/\"$p\" >/dev/null;"
                + " done");
            hostShell.run("rm -rf " + HostShell.quote(dataPath));
            hostShell.run("incus image delete " + HostShell.quote(incusAlias()) + " 2>/dev/null;"
                + " docker rmi " + HostShell.quote(dockerReference()) + " >/dev/null 2>&1;"
                + " true", 120);
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.VOLUME_ROOT,
            savedDataPath);
        for (LiveIncusHost host : new LiveIncusHost[] {remote, remoteA}) {
            if (host != null) {
                System.out.println("=== cleanup: shared objects -> " + host.releaseControllerSharedObjects());
                System.out.println("=== cleanup: authorized_keys -> " + host.releaseAuthorizedKeys());
            }
        }
        try {
            if (remote != null && enrolledFingerprint != null) {
                remote.removeTrustEntry(enrolledFingerprint);
            }
            if (remoteA != null && enrolledFingerprintA != null) {
                remoteA.removeTrustEntry(enrolledFingerprintA);
            }
        } catch (IOException ignored) {
            // nothing enrolled, nothing to remove
        }
    }

    /**
     * A workspace's home rides its plain backup and its cold migration between two real Incus hosts: the restored
     * copy on nightstrom holds the marker owned by the NEW record's mapped uid, and the moved workspace holds it there
     * with daystrom's copy gone.
     */
    @Test
    void aWorkspaceHomeTravelsWithItsBackupAndItsMigration() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int hostId = Models.get(ServerModel.class).findByName(HOST).get(ServerModel.ID);
            int hostAId = Models.get(ServerModel.class).findByName(HOST_A).get(ServerModel.ID);

            // 1. A workspace runs on daystrom and a marker is written into its home from inside.
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("memory_limit_mb", 512);
            settings.put("home_quota_mb", 256);
            int sourceId = workspaceRecord(runtimeImageRow(), settings, "live-travel", hostAId);
            travelled.add(sourceId);
            assertThat(service.deploy(sourceId).state())
                .as("step 1: the workspace runs on " + HOST_A).isEqualTo(ContainerState.RUNNING);
            String marker = "HHHOME-" + UUID.randomUUID();
            new InstanceExec().run(sourceId,
                "/bin/sh -c 'echo " + marker + " > " + WorkspaceKind.HOME_PATH + "/marker.txt'");
            String sourceHome = InstanceVolumes.hostPathFor(sourceId, WorkspaceKind.HOME_VOLUME);
            assertThat(shellA.run("cat " + HostShell.quote(sourceHome + "/marker.txt")).text())
                .as("step 1: daystrom's home holds the marker").isEqualTo(marker);

            // 2. A plain backup of the running workspace names its home.
            targetDir = createTempDirectory();
            int backupId = offHostBackup(sourceId, targetDir);
            Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
            assertThat(List.<Object>copyOf(RawValues.list(RawValues.map((Object) backup.get(InstanceBackupModel.SUMMARY))
                    .get(InstanceBackupModel.SUMMARY_HOST_VOLUMES))))
                .as("step 2: the backup carries the home").containsExactly(WorkspaceKind.HOME_VOLUME);

            // 3. Restored onto nightstrom, the new record's home holds the marker, owned by the host uid the NEW
            //    record's namespace id maps to there, and the restored workspace reads it from inside.
            int restoredId = backups.restoreToNew(backupId, "live-travel-restored", HOST).instanceId();
            travelled.add(restoredId);
            String restoredHome = InstanceVolumes.hostPathFor(restoredId, WorkspaceKind.HOME_VOLUME);
            assertThat(shell.run("cat " + HostShell.quote(restoredHome + "/marker.txt")).text())
                .as("step 3: nightstrom's restored home holds the marker").isEqualTo(marker);
            int restoredHostUid = WorkspaceUids.incusHostUid(WorkspaceUids.forInstance(restoredId),
                WorkspaceUids.incusSubuidBase(HOST, shell));
            assertThat(shell.run("stat -c %u " + HostShell.quote(restoredHome + "/marker.txt")).text().trim())
                .as("step 3: owned by the new record's mapped uid").isEqualTo(String.valueOf(restoredHostUid));
            assertThat(new InstanceExec().run(restoredId, "cat " + WorkspaceKind.HOME_PATH + "/marker.txt")
                    .output().trim())
                .as("step 3: the restored workspace reads it from inside").isEqualTo(marker);
            assertThat(shellA.run("cat " + HostShell.quote(sourceHome + "/marker.txt")).text())
                .as("step 3: the source keeps its own copy").isEqualTo(marker);
            service.destroyWithData(restoredId);
            travelled.remove(Integer.valueOf(restoredId));

            // 4. Migrated to nightstrom, the same record's home there holds the marker owned by its mapped uid,
            //    the workspace reads it from inside, and daystrom keeps no copy.
            new InstanceMigrations().migrateTo(sourceId, hostId);
            assertThat((Object) Models.get(InstanceModel.class).findById(sourceId).get(InstanceModel.SERVER_ID))
                .as("step 4: the record lives on " + HOST).isEqualTo(hostId);
            assertThat(shell.run("cat " + HostShell.quote(sourceHome + "/marker.txt")).text())
                .as("step 4: nightstrom's home holds the marker").isEqualTo(marker);
            int movedHostUid = WorkspaceUids.incusHostUid(WorkspaceUids.forInstance(sourceId),
                WorkspaceUids.incusSubuidBase(HOST, shell));
            assertThat(shell.run("stat -c %u " + HostShell.quote(sourceHome + "/marker.txt")).text().trim())
                .as("step 4: owned by the record's mapped uid there").isEqualTo(String.valueOf(movedHostUid));
            assertThat(new InstanceExec().run(sourceId, "cat " + WorkspaceKind.HOME_PATH + "/marker.txt")
                    .output().trim())
                .as("step 4: the moved workspace reads it from inside").isEqualTo(marker);
            assertThat(shellA.run("test -e " + HostShell.quote(sourceHome)).ok())
                .as("step 4: daystrom's copy of the home is gone").isFalse();

            // 5. Delete-with-data removes the moved home, and the backup goes with its artifact.
            service.destroyWithData(sourceId);
            travelled.remove(Integer.valueOf(sourceId));
            assertThat(shell.run("test -e " + HostShell.quote(sourceHome)).ok())
                .as("step 5: nightstrom's home is gone").isFalse();
            backups.delete(backupId);
        });
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
                .deploy(instanceId, "master", null, DeployTrigger.MANUAL);
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

    /** The test's runtime image row, made once per class database (both journeys use it). */
    private static int runtimeImageRow() {
        RuntimeImageModel model = Models.get(RuntimeImageModel.class);
        Row existing = model.find().where(RuntimeImageModel.NAME.eq("livetest-debian-13")).first();
        if (existing != null) {
            return existing.get(RuntimeImageModel.ID);
        }
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
        return workspaceRecord(imageId, settings, "live-workspace",
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
        // No volume is declared here on purpose: the deploy's own preparation declares the
        // home from the settings, and a hand-written row would prove the fixture instead.
        return row.get(InstanceModel.ID);
    }

    /** A filesystem backup target on the controller + one COMPLETE backup of the instance. */
    private static int offHostBackup(int ownerId, Path directory) {
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "workspace-live-target");
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
            return Files.createTempDirectory("workspace-backup-target");
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
