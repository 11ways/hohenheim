package be.elevenways.hohenheim.test.backup;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.test.host.LiveRemoteHost;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.crypto.KeyringGuard;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 2 parallel-gate clause a local directory could never meet: the control-plane
 * recovery archive -- this controller's own database AND the field-encryption keyring -- lands
 * on a GENUINELY DIFFERENT machine, and a controller that has lost both halves gets them back
 * from there with the encrypted values still decrypting.
 *
 * The proof that the archive really left this machine is not the target object's word: an
 * INDEPENDENT ssh exchange re-hashes the committed artifact and reports the remote's
 * {@code uname -n}, and the local staging directory is asserted to hold nothing, so the bytes
 * the restore reads could only have crossed the wire.
 */
class LiveControlPlaneOffHostBackupTest {

    private static final String REMOTE_HOST_NAME = "offhost-control-plane-host";
    private static final String HOOK_URL = "https://hooks.example.com/services/off-host-token-Z9";

    private static LiveRemoteHost remote;
    private static String remoteBase;
    private static String pinnedKeyLine;
    private static String identityPath;
    private static int remoteServerId;
    private static Path workspace;
    private static SqliteDatasource datasource;
    private static Path dbFile;
    private static Path keyringFile;

    @BeforeAll
    static void setUp() throws Exception {
        remote = LiveRemoteHost.configured();
        Assumptions.assumeTrue(remote != null,
            "no live remote host enrolled at " + LiveRemoteHost.CONFIG);

        workspace = Files.createTempDirectory("hh-offhost-control-plane");
        dbFile = workspace.resolve("live/hohenheim.db");
        keyringFile = workspace.resolve("live/field-encryption.keys");
        Files.createDirectories(dbFile.getParent());

        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(keyringFile));
        datasource = new SqliteDatasource("jdbc:sqlite:" + dbFile.toAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);

        // A per-run remote directory, so a crashed run can never make a later one pass on
        // somebody else's artifact.
        remoteBase = remote.backupPath() + "/cp-run-" + System.nanoTime();
        pinnedKeyLine = remote.scanVerifiedKeyLine();
        Properties properties = new Properties();
        try (var in = Files.newInputStream(LiveRemoteHost.CONFIG)) {
            properties.load(in);
        }
        identityPath = properties.getProperty("identity").trim();

        Db.run(datasource, () -> {
            Row host = remote.enrol(REMOTE_HOST_NAME);
            HostKeys.ScanResult scan = HostKeys.scanAndPin(host);
            assertThat(scan.fingerprint())
                .as("setup: the scanned key is the one the operator read on the host itself")
                .isEqualTo(remote.fingerprint());
            HostKeys.confirm(Models.get(ServerModel.class).findByName(REMOTE_HOST_NAME));
            remoteServerId = Models.get(ServerModel.class)
                .findByName(REMOTE_HOST_NAME).get(ServerModel.ID);
        });
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (remote != null && remoteBase != null) {
            remoteExec("rm -rf " + shellQuoted(remoteBase));
        }
        if (datasource != null) {
            datasource.close();
        }
        FieldEncryption.installKeyring(null);
        deleteTree(workspace);
    }

    @Test
    void theControlPlaneArchiveLivesOnAnotherMachineAndRestoresFromIt() throws Exception {
        EncryptionKeyring keyring = FieldEncryption.requireKeyring();

        // 1. A control-plane database holding a real encrypted secret.
        Db.run(datasource, () -> {
            KeyringGuard.runPerRegisteredModels();
            Model channels = Models.get(NotificationChannelModel.class);
            Row channel = channels.createEmptyRow();
            channel.set(NotificationChannelModel.NAME, "off-host hook");
            channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
            channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_SLACK);
            channel.set(NotificationChannelModel.URL, HOOK_URL);
            channels.save(channel);
        });

        // 2. The destination is the ENROLLED remote host, walked through the real ceremony in
        //    setUp: scan, out-of-band confirm, pin. Never a typed-in user@host.
        String[] key = new String[1];
        String[] recordedSha = new String[1];
        Path staging = workspace.resolve("staging");
        Db.run(datasource, () -> {
            Row targetRow = Models.get(BackupTargetModel.class).createEmptyRow();
            targetRow.set(BackupTargetModel.NAME, "control-plane-offhost");
            targetRow.set(BackupTargetModel.KIND, "hohenheim:ssh");
            targetRow.set(BackupTargetModel.SETTINGS, Map.of(
                "server", ServerModel.registryKeyOf(remoteServerId),
                "path", remoteBase));
            Models.get(BackupTargetModel.class).save(targetRow);
            assertThat((String) Models.get(ServerModel.class).findById(remoteServerId)
                    .get(ServerModel.ADMISSION))
                .as("step 2: the destination is a STORAGE host -- trusted and confirmed, never"
                    + " ADMITTED for placement, and it does not need to be")
                .isEqualTo(ServerModel.ADMISSION_BLOCKED);

            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "control-plane-offhost");
            BackupTarget target = ControlPlaneBackups.requireDestination();

            // 3. Back up. Completion means the REMOTE re-hashed the committed artifact.
            ControlPlaneBackups.Archive archive = run(() -> ControlPlaneBackups.backupNow(
                datasource, keyring, target, staging, 2, "control-plane-offhost"));
            key[0] = archive.key();
            recordedSha[0] = archive.sha256();
            assertThat(recordedSha[0]).as("step 3: with a remote-computed sha256")
                .matches("[0-9a-f]{64}");
        });

        // 3b. FIRST, before believing anything about the remote: nothing of the archive stayed
        //     here. A "successful" backup whose bytes are all still on this disk is the exact
        //     defect this rework exists to kill, and it must fail HERE, not later.
        assertThat(archivesUnder(workspace))
            .as("step 3b: no copy of the control-plane archive remains on the controller's own"
                + " disk -- the only copy is on the other machine")
            .isEmpty();

        // 4. The artifact is on the OTHER machine. This exchange is independent of the target
        //    object: its own ssh, its own sha256sum, and it names the host it ran on.
        String remoteFile = remoteBase + "/" + key[0];
        String[] answer = remoteExec("uname -n; sha256sum -b " + shellQuoted(remoteFile)
            + " | cut -d' ' -f1; stat -c %s " + shellQuoted(remoteFile)).split("\\s+");
        assertThat(answer[0])
            .as("step 4: the control-plane archive lives on a machine that is not this one")
            .isNotEqualTo(localHostname());
        assertThat(answer[1]).as("step 4: and its bytes there hash to what was recorded")
            .isEqualTo(recordedSha[0]);
        assertThat(Long.parseLong(answer[2]))
            .as("step 4: with real bytes, not an empty committed name").isGreaterThan(0L);
        assertThat(remoteExec("test -f " + shellQuoted(remoteFile + ".part")
            + " && echo YES || echo NO"))
            .as("step 4: and no staging debris survived the commit").isEqualTo("NO");

        // 6. Total loss of the controller: database, sidecars and keyring.
        datasource.close();
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Path.of(dbFile + "-wal"));
        Files.deleteIfExists(Path.of(dbFile + "-shm"));
        Files.deleteIfExists(keyringFile);
        assertThat(Files.exists(dbFile)).as("step 6: the database is gone").isFalse();
        assertThat(Files.exists(keyringFile)).as("step 6: and so is the keyring").isFalse();

        // 7. A fresh controller pulls both halves back ACROSS THE WIRE and reads the secret.
        //    The fetch runs through the INDEPENDENT ssh helper, not the backup target: the
        //    target resolves its destination from a `servers` row, which lives in the database
        //    that is gone. That is the honest total-loss order (and the documented limitation
        //    on restoreFromTarget) -- and it makes the bytes provably free of any local state.
        String originalPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH);
        String originalUrl = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.URL);
        String originalKeyFile = ServerSettings.VALUES.getValue(
            ServerSettings.Database.Encryption.KEY_FILE);
        Path fetched = workspace.resolve("fetched.zrec");
        try {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, dbFile.toString());
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL, "");
            ServerSettings.VALUES.setValue(
                ServerSettings.Database.Encryption.KEY_FILE, keyringFile.toString());
            remoteFetch(remoteFile, fetched);
            assertThat(Files.size(fetched))
                .as("step 7: the archive came back across the wire with real bytes")
                .isGreaterThan(0L);
            ControlPlaneBackups.restore(fetched);
        } finally {
            Files.deleteIfExists(fetched);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, originalPath);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                originalUrl == null ? "" : originalUrl);
            ServerSettings.VALUES.setValue(
                ServerSettings.Database.Encryption.KEY_FILE, originalKeyFile);
        }

        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(keyringFile));
        SqliteDatasource restored = new SqliteDatasource("jdbc:sqlite:" + dbFile.toAbsolutePath());
        Datasources.register(Datasources.DEFAULT, restored);
        try {
            Db.run(restored, () -> {
                KeyringGuard.runPerRegisteredModels();
                List<Row> rows = Models.get(NotificationChannelModel.class).find().all();
                assertThat(rows).as("step 7: the restored database holds the channel").hasSize(1);
                assertThat((String) rows.get(0).get(NotificationChannelModel.URL))
                    .as("step 7: and its encrypted webhook URL decrypts to its exact plaintext,"
                        + " from bytes that only ever existed on the other machine")
                    .isEqualTo(HOOK_URL);
            });
        } finally {
            restored.close();
        }
    }

    // -- plumbing -------------------------------------------------------------

    /** Stream a remote file down over the pinned ssh seam, with no controller state involved. */
    private static void remoteFetch(String remotePath, Path destination) throws IOException {
        try {
            List<String> argv = HostKeys.pinnedArgv("hohenheim-livetest",
                pinnedKeyLine, remote.target(),
                List.of("-o", "IdentitiesOnly=yes", "-i", identityPath,
                    "-o", "ConnectTimeout=10"));
            argv.add("cat " + shellQuoted(remotePath));
            Process process = new ProcessBuilder(argv)
                .redirectOutput(destination.toFile()).start();
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new IOException("remote fetch failed for " + remotePath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + remotePath);
        }
    }

    /**
     * A remote command over the product's OWN pinned ssh seam, but not through the backup
     * target -- so "the file is there" is never the target vouching for itself.
     */
    private static String remoteExec(String command) throws IOException {
        try {
            List<String> argv = HostKeys.pinnedArgv("hohenheim-livetest",
                pinnedKeyLine, remote.target(),
                List.of("-o", "IdentitiesOnly=yes", "-i", identityPath,
                    "-o", "ConnectTimeout=10"));
            argv.add(command);
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

    private static String shellQuoted(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private static List<Path> archivesUnder(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            List<Path> found = new ArrayList<>();
            walk.filter(path -> path.getFileName().toString().endsWith(".zrec"))
                .forEach(found::add);
            return found;
        }
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
