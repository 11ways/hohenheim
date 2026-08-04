package be.elevenways.hohenheim.test.backup;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.LiveIdOffsets;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.host.LiveRemoteHost;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The Phase 4 gate clause no single machine could meet: an instance backup exported to
 * a target on a GENUINELY DIFFERENT machine, verified there, and restored FROM there.
 *
 * The source instance runs on this workstation's daemon; the artifact's only home is
 * the remote host from {@link LiveRemoteHost}. The proof that it really left this
 * machine is not the target object's own word: the committed artifact is re-hashed BY
 * THE REMOTE, listed by an independent ssh exchange that also reports the remote's
 * {@code uname -n}, and the restore reads the bytes back across the wire before
 * anything is created locally.
 */
class LiveOffHostBackupTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;
    private static LiveRemoteHost remote;
    private static PrivateNetns netns;
    private static Path workRoot;
    private static String previousSnapshotPath;
    private static String previousStagingPath;
    private static String previousDataPath;
    private static String remoteBase;
    private static String pinnedKeyLine;
    private static String identityPath;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveRemoteHost.configured();
        Assumptions.assumeTrue(remote != null,
            "no live remote host enrolled at " + LiveRemoteHost.CONFIG);

        File db = File.createTempFile("hohenheim-offhost-backup-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        LiveIdOffsets.apply(datasource);
        HohenheimTestRuntime.ensureBooted();

        workRoot = Files.createTempDirectory("hohenheim-offhost-backup");
        previousSnapshotPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.SNAPSHOT_PATH);
        previousStagingPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Backup.STAGING_PATH);
        previousDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            workRoot.resolve("snapshots").toString());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH,
            workRoot.resolve("staging").toString());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            workRoot.resolve("controller-data").toString());
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            workRoot.resolve("test-keyring.keys")));

        // A per-run remote directory, so a crashed run can never make a later one pass
        // on somebody else's artifact.
        remoteBase = remote.backupPath() + "/run-" + System.nanoTime();
        pinnedKeyLine = remote.scanVerifiedKeyLine();
        java.util.Properties properties = new java.util.Properties();
        try (var in = Files.newInputStream(LiveRemoteHost.CONFIG)) {
            properties.load(in);
        }
        identityPath = properties.getProperty("identity").trim();

        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (remote != null && remoteBase != null) {
            remoteExec("rm -rf " + shellQuoted(remoteBase));
        }
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
        FieldEncryption.installKeyring(null);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.SNAPSHOT_PATH,
            previousSnapshotPath);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH,
            previousStagingPath);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            previousDataPath);
        deleteTree(workRoot);
    }

    /**
     * Export to the other machine, prove the artifact is THERE and nowhere here, then
     * restore a new instance from it.
     */
    @Test
    void anInstanceBacksUpToAnotherMachineAndRestoresFromIt() throws IOException {
        DockerClient docker = new DockerClient();
        Assumptions.assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        Assumptions.assumeTrue(imagePresent(docker), "alpine:latest not present locally");
        Assumptions.assumeTrue(netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();

            // 1. A configured SSH target pointing at the OTHER machine, pinned to the
            //    key whose digest an operator confirmed out of band.
            Row targetRow = Models.get(BackupTargetModel.class).createEmptyRow();
            targetRow.set(BackupTargetModel.NAME, "offhost");
            targetRow.set(BackupTargetModel.KIND, "hohenheim:ssh");
            targetRow.set(BackupTargetModel.SETTINGS, Map.of(
                "target", remote.target(), "path", remoteBase,
                "host_key", pinnedKeyLine));
            Models.get(BackupTargetModel.class).save(targetRow);
            BackupTarget target = BackupTargetKinds.targetOf(targetRow);
            run(() -> {
                target.healthCheck();
                return null;
            });
            assertThat(run(() -> remoteExec("test -d " + shellQuoted(remoteBase)
                + " && echo YES || echo NO")))
                .as("step 1: the health check created the base directory ON THE REMOTE")
                .isEqualTo("YES");

            int id = instanceRecord("offhost-source", alpineSettings());
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
                .assign(InstanceModel.BACKUP_TARGET_ID,
                    (Integer) targetRow.get(BackupTargetModel.ID))
                .updateAll();
            String handle = "hohenheim-instance-" + id;
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            Integer newId = null;
            String newHandle = null;
            try {
                // 2. Deploy the source instance locally and plant a marker in its volume.
                service.deploy(id);
                run(() -> exec(docker, handle, "echo off-host-precious > /data/marker"));

                // 3. Back it up. Completion means the REMOTE re-hashed the committed
                //    artifact and agreed with what we uploaded -- the product's own
                //    honesty rule, now spanning two machines.
                int backupId = backups.backupNow(id);
                Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
                assertThat((String) backup.get(InstanceBackupModel.STATUS))
                    .as("step 3: the backup row is complete")
                    .isEqualTo(InstanceBackupModel.STATUS_COMPLETE);
                String key = backup.get(InstanceBackupModel.REMOTE_KEY);
                String recordedSha = backup.get(InstanceBackupModel.SHA256);
                assertThat(recordedSha).as("step 3: with a remote-computed sha256")
                    .matches("[0-9a-f]{64}");
                assertThat(service.liveStatus(id).state())
                    .as("step 3: and the source instance runs again after the cold capture")
                    .isEqualTo(ContainerState.RUNNING);

                // 4. The artifact is on the OTHER machine. This exchange is independent
                //    of the target object: its own ssh, its own sha256sum, and it names
                //    the host it ran on, which is not this one.
                String remoteFile = remoteBase + "/" + key;
                String[] answer = run(() -> remoteExec("uname -n; sha256sum -b "
                    + shellQuoted(remoteFile) + " | cut -d' ' -f1; stat -c %s "
                    + shellQuoted(remoteFile))).split("\\s+");
                assertThat(answer[0])
                    .as("step 4: the artifact lives on a machine that is not this one")
                    .isNotEqualTo(localHostname());
                assertThat(answer[1])
                    .as("step 4: and its bytes there hash to what the row recorded")
                    .isEqualTo(recordedSha);
                assertThat(Long.parseLong(answer[2]))
                    .as("step 4: with real bytes, not an empty committed name")
                    .isGreaterThan(0L);
                assertThat(run(() -> remoteExec("test -f " + shellQuoted(remoteFile
                    + ".part") + " && echo YES || echo NO")))
                    .as("step 4: and no staging debris survived the commit")
                    .isEqualTo("NO");

                // 5. Nothing of the artifact is left on this machine: staging is empty,
                //    so the restore below can only come across the wire.
                assertThat(run(() -> archivesUnder(workRoot.resolve("staging"))))
                    .as("step 5: no local copy of the archive remains").isEmpty();

                // 6. Restore to a NEW instance, reading the bytes back from the other
                //    machine and re-verifying them before anything is created.
                newId = backups.restoreToNew(backupId, "offhost-clone", null);
                newHandle = "hohenheim-instance-" + newId;
                assertThat(newId).as("step 6: the restore produced a NEW instance")
                    .isNotEqualTo(id);
                Row restored = Models.get(InstanceModel.class).findById(newId);
                assertThat((String) restored.get(InstanceModel.STATUS))
                    .as("step 6: which is running").isEqualTo(InstanceModel.STATUS_RUNNING);
                String finalHandle = newHandle;
                assertThat(run(() -> exec(docker, finalHandle, "cat /data/marker")))
                    .as("step 6: with the volume data that crossed the wire twice")
                    .isEqualTo("off-host-precious");

                // 7. A target whose pin does NOT match the real host reaches nothing, and
                //    exists() says so by RAISING. Answering "absent" for a host it never
                //    reached is the retention sweep deleting a row whose artifact is
                //    fine, and the corruption gate accepting a backup nobody looked at.
                Row decoyRow = Models.get(BackupTargetModel.class).createEmptyRow();
                decoyRow.set(BackupTargetModel.NAME, "offhost-decoy");
                decoyRow.set(BackupTargetModel.KIND, "hohenheim:ssh");
                decoyRow.set(BackupTargetModel.SETTINGS, Map.of(
                    "target", remote.target(), "path", remoteBase,
                    "host_key", run(LiveOffHostBackupTest::foreignHostKeyLine)));
                Models.get(BackupTargetModel.class).save(decoyRow);
                BackupTarget decoy = BackupTargetKinds.targetOf(decoyRow);
                assertThat(catchThrowable(() -> decoy.exists(key)))
                    .as("step 7: an unreachable target RAISES instead of answering absent")
                    .isInstanceOfSatisfying(IOException.class, failure ->
                        assertThat(failure.getMessage())
                            .contains("Host key verification failed"));

                // 8. exists() answers about the REMOTE, and delete really removes it
                //    there -- a retention sweep acts on this answer.
                assertThat(run(() -> target.exists(key)))
                    .as("step 8: the target reports the remote artifact present").isTrue();
                backups.delete(backupId);
                assertThat(run(() -> remoteExec("test -f " + shellQuoted(remoteFile)
                    + " && echo YES || echo NO")))
                    .as("step 8: and deleting the backup removed it FROM THE REMOTE")
                    .isEqualTo("NO");
                assertThat(run(() -> target.exists(key)))
                    .as("step 8: which the target now reports as absent").isFalse();
            } finally {
                destroyQuietly(service, id);
                cleanup(docker, handle);
                if (newId != null) {
                    destroyQuietly(service, newId);
                    cleanup(docker, newHandle);
                }
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    /**
     * A remote command over the product's OWN pinned ssh seam, but not through the
     * backup target -- so "the file is there" is never the target vouching for itself.
     */
    private static String remoteExec(String command) throws IOException {
        try {
            List<String> argv = HostKeys.pinnedArgv("hohenheim-livetest",
                pinnedKeyLine, remote.target(),
                List.of("-o", "IdentitiesOnly=yes", "-i", identityPath,
                    "-o", "ConnectTimeout=10"));
            argv.add(command);
            // stderr stays SEPARATE: the ssh client prints advisories there (the
            // post-quantum-kex warning against an older sshd, for one) and folding them
            // into stdout would corrupt every answer this helper is asked for.
            Process process = new ProcessBuilder(argv).start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            String errors = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("remote command timed out: " + command);
            }
            if (process.exitValue() != 0) {
                throw new IOException("remote command failed (" + process.exitValue()
                    + "): " + command + " -- " + errors.trim());
            }
            return output.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted running " + command);
        }
    }

    private static String localHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "";
        }
    }

    /** A freshly minted, genuinely valid ed25519 public key that is NOT the host's. */
    private static String foreignHostKeyLine() throws IOException {
        Path directory = Files.createTempDirectory("hohenheim-offhost-decoy");
        try {
            Path key = directory.resolve("decoy");
            Process process = new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519",
                "-N", "", "-C", "decoy", "-f", key.toString())
                .redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("ssh-keygen did not produce a decoy key");
            }
            return HostKeys.keyLineOf(Files.readString(directory.resolve("decoy.pub"),
                StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted minting a decoy host key");
        } finally {
            deleteTree(directory);
        }
    }

    private static String shellQuoted(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private static List<Path> archivesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            List<Path> found = new ArrayList<>();
            walk.filter(path -> path.getFileName().toString().endsWith(".hib"))
                .forEach(found::add);
            return found;
        }
    }

    private static int instanceRecord(String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static Map<String, Object> alpineSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("command", "sleep 300");
        settings.put("container_port", 8080);
        settings.put("volumes", Map.of("data", "/data"));
        return settings;
    }

    private static String exec(DockerClient docker, String handle, String command)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(handle, List.of("sh", "-c", command));
        assertThat(result.exitCode())
            .as("exec '" + command + "' succeeds (stderr: " + result.stderr() + ")")
            .isEqualTo(0);
        return result.stdout().trim();
    }

    private static boolean imagePresent(DockerClient docker) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains("alpine:latest")) {
                return true;
            }
        }
        return false;
    }

    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    private static <T> T run(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void destroyQuietly(InstanceService service, int id) {
        try {
            service.destroy(id);
        } catch (RuntimeException ignored) {
            // the daemon-level cleanup below is the backstop
        }
    }

    private static void cleanup(DockerClient docker, String handle) {
        try {
            docker.removeContainer(handle, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeVolume(handle + "-vol-data", true);
        } catch (IOException ignored) {
            // already gone
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(walk.toList());
            for (int index = paths.size() - 1; index >= 0; index--) {
                Files.deleteIfExists(paths.get(index));
            }
        } catch (IOException ignored) {
            // a leftover temp directory is not worth failing a green run over
        }
    }
}
