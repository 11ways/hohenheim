package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The control-plane wiring over zenit's RecoveryArchive: one archive carrying hohenheim's own
 * database AND the field-encryption keyring, UPLOADED to a configured backup target, restorable
 * back to actual decrypted plaintext from that target.
 *
 * A filesystem target keeps this class hermetic; that it works across a genuinely different
 * machine is {@code LiveControlPlaneOffHostBackupTest}'s job. What IS proven here and matters
 * either way: after a successful backup, nothing of the archive is left on the local disk.
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
    void backupRestoreJourneyThroughAConfiguredTarget() throws Exception {
        Path workspace = Files.createTempDirectory("hh-cp-journey");
        Path dbFile = workspace.resolve("live/hohenheim.db");
        Path keyringFile = workspace.resolve("live/field-encryption.keys");
        Path staging = workspace.resolve("staging");
        Path targetRoot = workspace.resolve("elsewhere");

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

        // 2. The archive is UPLOADED to the target, and the target's own re-hash is what
        //    "verified" means. Two older archives are already there, so retention has
        //    something to prune -- ON THE TARGET, which is the only place they exist.
        BackupTarget target = new FilesystemBackupTarget(targetRoot);
        Files.createDirectories(targetRoot.resolve("control-plane"));
        Files.writeString(targetRoot.resolve("control-plane/control-plane-20200101-000000.zrec"),
            "old-1");
        Files.writeString(targetRoot.resolve("control-plane/control-plane-20200102-000000.zrec"),
            "old-2");

        ControlPlaneBackups.Archive archive =
            ControlPlaneBackups.backupNow(datasource, keyring, target, staging, 2, "test-target");

        assertThat(archive.key()).as("step 2: the archive landed under the control-plane prefix")
            .startsWith(ControlPlaneBackups.KEY_PREFIX);
        assertThat(target.storedSha256(archive.key()))
            .as("step 2: and the bytes the TARGET holds hash to what was recorded")
            .isEqualTo(archive.sha256());
        assertThat(target.list(ControlPlaneBackups.KEY_PREFIX))
            .as("step 2: retention keeps the newest 2 archives ON THE TARGET")
            .containsExactlyInAnyOrder(
                "control-plane/control-plane-20200102-000000.zrec", archive.key());

        // 3. THE POINT: nothing of the archive is on the local disk. A local copy would die in
        //    the very failure this backup exists for.
        assertThat(archivesUnder(staging))
            .as("step 3: the staging directory holds no archive -- the only copy is on the target")
            .isEmpty();
        assertThat(archivesUnder(workspace.resolve("live")))
            .as("step 3: and none was written beside the database either").isEmpty();

        // 4. Total loss of the working copy: database, sidecars AND keyring.
        datasource.close();
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Path.of(dbFile + "-wal"));
        Files.deleteIfExists(Path.of(dbFile + "-shm"));
        Files.deleteIfExists(keyringFile);

        // 5. Restore reads the archive back FROM the target and puts both halves where the
        //    settings say they belong.
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
            target.retrieve(archive.key(), fetched);
            RecoveryArchive.Manifest manifest = ControlPlaneBackups.restore(fetched);
            assertThat(manifest.activeKeyId())
                .as("step 5: the restored archive names the key it was made under")
                .isEqualTo(archive.manifest().activeKeyId());
        } finally {
            Files.deleteIfExists(fetched);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, originalPath);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.URL,
                originalUrl == null ? "" : originalUrl);
            ServerSettings.VALUES.setValue(
                ServerSettings.Database.Encryption.KEY_FILE, originalKeyFile);
        }

        // 6. The restored pair passes the boot guard and the secret reads back as the ACTUAL
        //    decrypted plaintext -- not merely "the file exists".
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(keyringFile));
        SqliteDatasource restored = new SqliteDatasource("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try {
            Db.run(restored, () -> {
                KeyringGuard.runPerRegisteredModels();
                List<Row> rows = Models.get(NotificationChannelModel.class).find().all();
                assertThat(rows).as("step 6: the restored database holds the channel").hasSize(1);
                assertThat((String) rows.get(0).get(NotificationChannelModel.URL))
                    .as("step 6: the encrypted webhook URL decrypts to its exact plaintext")
                    .isEqualTo(HOOK_URL);
            });
        } finally {
            restored.close();
        }
    }

    /**
     * No destination and a wrong destination are both loud refusals, and neither degrades to a
     * local write. The positive anchor is the journey above: a configured target works.
     */
    @Test
    void anUnconfiguredOrUnknownDestinationIsRefusedByName() throws Exception {
        Path workspace = Files.createTempDirectory("hh-cp-destination");
        Path dbFile = workspace.resolve("hohenheim.db");
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + dbFile.toAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);

        String original = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET);
        try {
            Db.run(datasource, () -> {
                Row row = Models.get(BackupTargetModel.class).createEmptyRow();
                row.set(BackupTargetModel.NAME, "somewhere-else");
                row.set(BackupTargetModel.KIND, "hohenheim:filesystem");
                row.set(BackupTargetModel.SETTINGS,
                    java.util.Map.of("path", workspace.resolve("elsewhere").toString()));
                Models.get(BackupTargetModel.class).save(row);

                // 1. Unset: refused, naming the setting and saying there is no local fallback.
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "");
                assertThatThrownBy(ControlPlaneBackups::requireDestination)
                    .as("step 1: an unconfigured destination refuses instead of writing locally")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("control_plane_backup_target")
                    .hasMessageContaining("does not survive losing this host")
                    .hasMessageContaining("somewhere-else");
                assertThat(ControlPlaneBackups.configuredDestinationName())
                    .as("step 1: and the dashboard can see it is unconfigured").isNull();

                // 2. A typo names no target: refused, listing the ones that DO exist, so it
                //    never reads like a product with no backup targets at all.
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "typo");
                assertThatThrownBy(ControlPlaneBackups::requireDestination)
                    .as("step 2: an unknown target name refuses and lists the known ones")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("'typo'")
                    .hasMessageContaining("somewhere-else");

                // 3. The configured one resolves to a live target.
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "somewhere-else");
                assertThat(ControlPlaneBackups.requireDestination())
                    .as("step 3: a correctly named target resolves").isNotNull();
            });
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET,
                original == null ? "" : original);
            datasource.close();
        }
    }

    /**
     * The dashboard must say so BEFORE 02:30, and must stay silent once a destination exists.
     * A collector that always fires is the same defect as one that never does.
     */
    @Test
    void theDashboardRaisesAnItemWhileNoDestinationIsConfigured() {
        String original = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET);
        try {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "");
            List<AttentionItem> unconfigured = new ArrayList<>();
            AttentionCollector.controlPlaneBackupDestination(unconfigured);
            assertThat(unconfigured)
                .as("step 1: an unconfigured off-host destination is a dashboard item")
                .hasSize(1);
            assertThat(unconfigured.get(0).severity())
                .as("step 1: at error severity -- there is no backup at all").isEqualTo("error");
            assertThat(unconfigured.get(0).url())
                .as("step 1: pointing at where it is fixed").isEqualTo("/admin/settings");

            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, "somewhere");
            List<AttentionItem> configured = new ArrayList<>();
            AttentionCollector.controlPlaneBackupDestination(configured);
            assertThat(configured)
                .as("step 2: and it goes silent once a destination is named").isEmpty();
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET,
                original == null ? "" : original);
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

    private static List<Path> archivesUnder(Path root) throws java.io.IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".zrec")).toList();
        }
    }
}
