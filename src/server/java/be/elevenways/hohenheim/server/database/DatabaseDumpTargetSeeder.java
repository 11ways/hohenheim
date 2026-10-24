package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.backup.FilesystemTargetKind;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.seed.SeedContext;
import be.elevenways.zenit.server.orm.seed.Seeder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps an upgraded install's nightly managed-database dumps: a filesystem backup target at {@code database.backup_path}
 * that {@code database.backup_target} names while nothing else does.
 *
 * AIDEV-NOTE: the dump setting is host-only, and the framework lets only default.dry, a hand edit of local.dry or the
 * environment set a host-only key (SettingAccess refuses every code write), so this never persists the setting.
 * The once block creates the target record; every boot then applies its name as the setting's value only while no
 * source set one, the HohenheimSettingsBoot.applyFrameworkDefaults shape. An operator's value always wins, and a
 * blank one counts as unset, as DatabaseBackups.configuredTargetName reads it.
 *
 * AIDEV-NOTE: an upgrade is an install that already has managed databases at the first boot of this seeder; a fresh
 * install (none yet) gets nothing and keeps the required choice. Nothing is faked: when the directory cannot be made
 * or written, no target is created, the setting stays unset and every dump still fails with BACKUP_FAILED. The block
 * is ledgered either way, so a later fix of the path means creating the target by hand.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DatabaseDumpTargetSeeder implements Seeder {

    /** The once key of the upgrade's target creation. */
    public static final String SEED_KEY = "hohenheim.database-dumps.local-target";

    /** The name of the filesystem target an upgrade gets. */
    public static final String TARGET_NAME = "local-database-dumps";

    public DatabaseDumpTargetSeeder() {}

    @Override
    public void seed(@NonNull SeedContext ctx) {
        ctx.once(SEED_KEY, DatabaseDumpTargetSeeder::createUpgradeTarget);
        adoptUpgradeTarget();
    }

    /** The once block: creates the target on an upgraded install whose dump setting is unset, when its path works. */
    public static void createUpgradeTarget() {
        if (DatabaseBackups.configuredTargetName() != null || DatabaseBackups.targetRow(TARGET_NAME) != null
            || Models.get(DatabaseModel.class).find().count() == 0) {
            return;
        }
        Path root = DatabaseBackups.backupRoot().toAbsolutePath().normalize();
        Map<String, Object> settings = new LinkedHashMap<>(Map.of(FilesystemTargetKind.PATH.getName(),
            root.toString()));
        try {
            new FilesystemTargetKind().targetFor(settings).healthCheck();
        } catch (IOException | RuntimeException unusable) {
            Blast.log("DUMP: no backup target was created at", root, "for the managed-database dumps:",
                unusable.getMessage(), "- they fail until", HohenheimSettings.Database.BACKUP_TARGET.getPath(),
                "names a target");
            return;
        }
        BackupTargetModel targets = Models.get(BackupTargetModel.class);
        Row row = targets.createEmptyRow();
        row.set(BackupTargetModel.NAME, TARGET_NAME);
        row.set(BackupTargetModel.KIND, FilesystemTargetKind.ID.toString());
        row.set(BackupTargetModel.SETTINGS, settings);
        targets.save(row);
        Blast.log("DUMP: managed-database dumps now go to the filesystem backup target", TARGET_NAME, "at", root);
    }

    /** Names the upgrade's target in the dump setting while no source set a value and the target exists. */
    public static void adoptUpgradeTarget() {
        if (DatabaseBackups.configuredTargetName() == null && DatabaseBackups.targetRow(TARGET_NAME) != null) {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_TARGET, TARGET_NAME);
        }
    }
}
