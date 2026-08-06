package be.elevenways.hohenheim.test.database;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.backup.RecoveryArchive;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.crypto.KeyringGuard;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The control-plane wiring over zenit's RecoveryArchive: one archive carrying hohenheim's own
 * database AND the field-encryption keyring, restorable back to actual decrypted plaintext
 * through the configured settings paths -- the unit whose loss {@code M047}'s encryption wave
 * made unrecoverable without it.
 */
class ControlPlaneBackupTest {

    private static final String HOOK_URL = "https://hooks.example.com/services/cp-backup-token-X1";

    @BeforeAll
    static void installTempKeyring() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-cp-backup").resolve("keys.dry")));
    }

    @AfterAll
    static void revertKeyring() {
        FieldEncryption.installKeyring(null);
    }

    @Test
    void backupRestoreJourneyThroughConfiguredPaths() throws Exception {
        Path workspace = Files.createTempDirectory("hh-cp-journey");
        Path dbFile = workspace.resolve("live/hohenheim.db");
        Path keyringFile = workspace.resolve("live/field-encryption.keys");
        Path backupDir = workspace.resolve("backups");

        // 1. A migrated control-plane database with a real encrypted secret, under a real
        //    keyring file, marker recorded by the boot guard.
        Files.createDirectories(dbFile.getParent());
        EncryptionKeyring keyring = EncryptionKeyring.loadOrCreate(keyringFile);
        FieldEncryption.installKeyring(keyring);
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + dbFile.toAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        Db.run(datasource, () -> {
            KeyringGuard.runPerRegisteredModels();
            Model channels = Models.get(NotificationChannelModel.class);
            Row channel = channels.createEmptyRow();
            channel.set(NotificationChannelModel.NAME, "cp-backup hook");
            channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
            channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_SLACK);
            channel.set(NotificationChannelModel.URL, HOOK_URL);
            channels.save(channel);
        });

        // 2. The backup wiring writes ONE archive carrying both halves, already verified,
        //    and prunes old archives per retention (stamp names sort chronologically).
        Files.createDirectories(backupDir);
        Files.writeString(backupDir.resolve("control-plane-20200101-000000.zrec"), "old-1");
        Files.writeString(backupDir.resolve("control-plane-20200102-000000.zrec"), "old-2");
        Path archive = ControlPlaneBackups.backupNow(datasource, keyring, backupDir, 2);
        assertThat(archive).as("step 2: the archive was written").exists();
        RecoveryArchive.Manifest manifest = RecoveryArchive.verify(archive);
        assertThat(manifest.activeKeyId())
            .as("step 2: the manifest names the live active key")
            .isEqualTo(keyring.activeKeyId());
        try (var files = Files.list(backupDir)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted().toList())
                .as("step 2: retention keeps the newest 2 archives, pruning the oldest dummy")
                .containsExactly("control-plane-20200102-000000.zrec",
                    archive.getFileName().toString());
        }

        // 3. Total loss of the working copy: database, sidecars AND keyring.
        datasource.close();
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Path.of(dbFile + "-wal"));
        Files.deleteIfExists(Path.of(dbFile + "-shm"));
        Files.deleteIfExists(keyringFile);

        // 4. The operator restore path (ServerMain --restore-control-plane) resolves BOTH
        //    targets from the settings, exactly like a real boot would.
        String originalPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH);
        String originalUrl = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.URL);
        String originalKeyFile = ServerSettings.VALUES.getValue(
            ServerSettings.Database.Encryption.KEY_FILE);
        try {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, dbFile.toString());
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL, "");
            ServerSettings.VALUES.setValue(
                ServerSettings.Database.Encryption.KEY_FILE, keyringFile.toString());
            ControlPlaneBackups.restore(archive);
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, originalPath);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                originalUrl == null ? "" : originalUrl);
            ServerSettings.VALUES.setValue(
                ServerSettings.Database.Encryption.KEY_FILE, originalKeyFile);
        }

        // 5. The restored pair passes the boot guard and the secret reads back as the ACTUAL
        //    decrypted plaintext -- not merely "the file exists".
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(keyringFile));
        SqliteDatasource restored = new SqliteDatasource("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try {
            Db.run(restored, () -> {
                KeyringGuard.runPerRegisteredModels();
                List<Row> rows = Models.get(NotificationChannelModel.class).find().all();
                assertThat(rows).as("step 5: the restored database holds the channel").hasSize(1);
                assertThat((String) rows.get(0).get(NotificationChannelModel.URL))
                    .as("step 5: the encrypted webhook URL decrypts to its exact plaintext")
                    .isEqualTo(HOOK_URL);
            });
        } finally {
            restored.close();
        }
    }

    @Test
    void databaseFileResolutionRefusesWhatRestoreCannotReplace() {
        String originalPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH);
        String originalUrl = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.URL);
        try {
            // A plain sqlite URL resolves to its file, query parameters stripped.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                "jdbc:sqlite:/tmp/cp-test.db?foreign_keys=on");
            assertThat(ControlPlaneBackups.databaseFile())
                .isEqualTo(Path.of("/tmp/cp-test.db"));

            // A non-sqlite URL is refused: restore replaces a FILE, it cannot address this.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                "jdbc:postgresql://localhost/hh");
            assertThatThrownBy(ControlPlaneBackups::databaseFile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jdbc:sqlite:");

            // A memory URL names no file at all.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                "jdbc:sqlite::memory:");
            assertThatThrownBy(ControlPlaneBackups::databaseFile)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILE");
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, originalPath);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                originalUrl == null ? "" : originalUrl);
        }
    }
}
