package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.BackupStatus;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.backup.FilesystemTargetKind;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseDumpTargetSeeder;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.notifications.DeliveryChannel;
import be.elevenways.zenit.notifications.server.DeliveryModel;
import be.elevenways.zenit.notifications.server.NotificationDispatcher;
import be.elevenways.zenit.notifications.server.NotificationInboxOwners;
import be.elevenways.zenit.notifications.server.Notifications;
import be.elevenways.zenit.notifications.server.transport.DsnOrigin;
import be.elevenways.zenit.notifications.server.transport.TransportTypes;
import be.elevenways.zenit.server.orm.seed.Seeds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An upgraded install whose dump setting is unset gets a filesystem target at its backup path and the setting names
 * it; a set setting is left alone, and an unwritable path fakes nothing, so the dump still alerts BACKUP_FAILED.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DatabaseDumpTargetSeederTest {

    private Path scratch;
    private String previousPath;

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @BeforeEach
    void unsetTarget() throws Exception {
        this.scratch = Files.createTempDirectory("hohenheim-dump-seed");
        this.previousPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_PATH);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_TARGET, "");
    }

    @AfterEach
    void restore() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_TARGET, "");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, this.previousPath);
        Models.get(DatabaseDumpModel.class).find().delete();
        Models.get(BackupTargetModel.class).find()
            .where(BackupTargetModel.NAME.eq(DatabaseDumpTargetSeeder.TARGET_NAME)).delete();
        FileTrees.deleteQuietly(this.scratch);
    }

    /** Runs the seeder as a boot does: its once block (the fresh test database already ledgered it) and its adoption. */
    private static void upgradeBoot() {
        DatabaseDumpTargetSeeder.createUpgradeTarget();
        Seeds.run(HohenheimDatabase.datasource(), new DatabaseDumpTargetSeeder());
    }

    private static Row seededTarget() {
        return Models.get(BackupTargetModel.class).find()
            .where(BackupTargetModel.NAME.eq(DatabaseDumpTargetSeeder.TARGET_NAME)).first();
    }

    @Test
    void anUpgradeWithoutATargetKeepsItsNightlyDumpsOnAFilesystemTargetAtItsBackupPath() throws Exception {
        Path backups = this.scratch.resolve("backups");
        Path legacy = backups.resolve("upgradeshop").resolve("20260101T030000Z.sql");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "-- yesterday's local dump\n");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, backups.toString());
        DumpTargetFixture.databaseRow("upgradeshop", DatabaseModel.ENGINE_POSTGRES);
        try {
            // 1. The upgrade's boot creates the filesystem target at the backup path, and the setting names it.
            upgradeBoot();
            Row target = seededTarget();
            assertThat(target).as("step 1: the target exists").isNotNull();
            assertThat((String) target.get(BackupTargetModel.KIND)).as("step 1: a filesystem target")
                .isEqualTo(FilesystemTargetKind.ID.toString());
            assertThat(((Map<?, ?>) target.get(BackupTargetModel.SETTINGS)).get(FilesystemTargetKind.PATH.getName()))
                .as("step 1: at the backup path").isEqualTo(backups.toAbsolutePath().normalize().toString());
            assertThat(DatabaseBackups.configuredTargetName()).as("step 1: the setting names it")
                .isEqualTo(DatabaseDumpTargetSeeder.TARGET_NAME);

            // 2. A second boot changes nothing: one target, the same name.
            upgradeBoot();
            assertThat(Models.get(BackupTargetModel.class).find()
                .where(BackupTargetModel.NAME.eq(DatabaseDumpTargetSeeder.TARGET_NAME)).count())
                .as("step 2: still one target").isOne();

            // 3. The next dump lands on it, encrypted and complete, and yesterday's local dump is untouched.
            Row dump = DatabaseBackups.backUp(DumpTargetFixture.scriptedDumps(new AtomicInteger()), "upgradeshop");
            assertThat((String) dump.get(DatabaseDumpModel.STATUS)).as("step 3: complete")
                .isEqualTo(BackupStatus.COMPLETE.key());
            assertThat((Integer) dump.get(DatabaseDumpModel.TARGET_ID)).as("step 3: on the seeded target")
                .isEqualTo(target.get(BackupTargetModel.ID));
            Path artifact = backups.resolve(String.valueOf((Object) dump.get(DatabaseDumpModel.REMOTE_KEY)));
            assertThat(Files.isRegularFile(artifact)).as("step 3: the artifact is under the backup path").isTrue();
            assertThat(Files.readString(artifact, StandardCharsets.ISO_8859_1))
                .as("step 3: encrypted").doesNotContain("CREATE TABLE");
            assertThat(Files.readString(legacy)).as("step 3: the old local dump stays as it was")
                .isEqualTo("-- yesterday's local dump\n");
        } finally {
            Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("upgradeshop")).delete();
        }
    }

    @Test
    void aSetTargetIsLeftAlone() throws Exception {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, this.scratch.toString());
        DumpTargetFixture.databaseRow("chosenshop", DatabaseModel.ENGINE_POSTGRES);
        try (DumpTargetFixture chosen = DumpTargetFixture.install("operator-chosen-target")) {
            upgradeBoot();
            assertThat(seededTarget()).as("no target is created beside the chosen one").isNull();
            assertThat(DatabaseBackups.configuredTargetName()).as("the setting keeps the operator's choice")
                .isEqualTo(chosen.name());
        } finally {
            Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("chosenshop")).delete();
        }
    }

    @Test
    void anUnwritableBackupPathFakesNoTargetAndTheDumpStillAlerts() throws Exception {
        // A directory under a regular file can never be made, whoever runs the test.
        Path blocker = Files.writeString(this.scratch.resolve("not-a-directory"), "x");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, blocker.resolve("backups").toString());
        DumpTargetFixture.databaseRow("blockedshop", DatabaseModel.ENGINE_POSTGRES);
        UUID admin = ApiSupport.user("dump-seed-admin-" + UUID.randomUUID() + "@hohenheim.local", "Dump Seed Admin");
        GrantService.createDirectGrant(GrantSubjectType.USER, admin, HohenheimSources.ADMIN_ACCESS.value(), true);
        Notifications.install(new NotificationDispatcher(Map.of(
            DeliveryChannel.WEBHOOK, List.of(TransportTypes.create("webhook://default", DsnOrigin.OPERATOR))), 1, true));
        try {
            // 1. The boot makes no target and leaves the setting unset.
            upgradeBoot();
            assertThat(seededTarget()).as("step 1: no target faked").isNull();
            assertThat(DatabaseBackups.configuredTargetName()).as("step 1: the setting stays unset").isNull();

            // 2. The nightly dump fails naming the setting, and the administrator is alerted BACKUP_FAILED.
            DatabaseService service = DumpTargetFixture.scriptedDumps(new AtomicInteger());
            assertThat(DatabaseBackups.backUpOrAlert(service, "blockedshop", InstanceOperationLock.Contention.QUEUE))
                .as("step 2: the dump fails")
                .contains(HohenheimSettings.Database.BACKUP_TARGET.getPath());
            assertThat(Models.get(DeliveryModel.class).find()
                .where(DeliveryModel.NOTIFICATION_KEY.eq(HohenheimIds.id(NotificationEvents.BACKUP_FAILED.token())
                    .toString()))
                .where(DeliveryModel.RECIPIENT.eq(NotificationInboxOwners.userKey(admin))).count())
                .as("step 2: BACKUP_FAILED reached the administrator").isOne();
        } finally {
            Notifications.install(null);
            Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("blockedshop")).delete();
        }
    }
}
