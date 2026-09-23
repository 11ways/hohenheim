package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds the database an install that applied Hohenheim's migrations only up to one version is in.
 *
 * AIDEV-NOTE: an older install is built by running the DISCOVERED set minus the Hohenheim migrations
 * above the version, never by deleting ledger rows off a fully migrated file. Removing a MIDDLE row
 * produces a history no real install can have (a migration pending behind applied ones of its own
 * stream), which the strict integrity check rightly refuses as out of order; that is how the
 * rehearsal fixture broke the day a migration was appended after the one it regressed. Other
 * streams (zenit core, the modules) stay whole: they number independently and are not what an
 * upgrade of this app's jar moves.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstallsAt {

    private InstallsAt() {
    }

    /**
     * Migrate a new SQLite file at the path exactly as far as an install that applied Hohenheim's
     * stream through {@code version}, closing the datasource afterwards.
     *
     * @throws IllegalStateException when that partial migration does not succeed
     */
    public static void migrateThrough(@NonNull Path file, @NonNull String version) {
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            new MigrationRunner(datasource, discoveredThrough(datasource.getDatasourceIdentifier(), version))
                .migrate().requireSuccess();
        } finally {
            datasource.close();
        }
    }

    /**
     * @return every discovered migration except the Hohenheim ones whose version is above {@code version}
     */
    public static @NonNull List<Supplier<Migration>> discoveredThrough(@NonNull String datasourceIdentifier,
                                                                      @NonNull String version) {
        List<Supplier<Migration>> kept = new ArrayList<>();
        for (Supplier<Migration> supplier : MigrationRunner.discoverMigrations(datasourceIdentifier)) {
            Migration migration = supplier.get();
            if (!isHohenheim(migration) || migration.getVersion().compareTo(version) <= 0) {
                kept.add(supplier);
            }
        }
        return kept;
    }

    /**
     * @return this app's discovered migrations, oldest first (the stream's versions are zero-padded,
     *         so text order is version order)
     */
    public static @NonNull List<Migration> hohenheimMigrations(@NonNull String datasourceIdentifier) {
        List<Migration> own = new ArrayList<>();
        for (Supplier<Migration> supplier : MigrationRunner.discoverMigrations(datasourceIdentifier)) {
            Migration migration = supplier.get();
            if (isHohenheim(migration)) {
                own.add(migration);
            }
        }
        own.sort(Comparator.comparing(Migration::getVersion));
        return own;
    }

    /** Run a body under a temporary {@code database.migration_integrity} mode, restoring the previous one. */
    public static void withIntegrityMode(@NonNull String mode, @NonNull Runnable body) {
        String previous = ServerSettings.VALUES.getValue(ServerSettings.Database.MIGRATION_INTEGRITY);
        ServerSettings.VALUES.setValue(ServerSettings.Database.MIGRATION_INTEGRITY, mode);
        try {
            body.run();
        } finally {
            ServerSettings.VALUES.setValue(ServerSettings.Database.MIGRATION_INTEGRITY, previous);
        }
    }

    private static boolean isHohenheim(@NonNull Migration migration) {
        return HohenheimMigration.STREAM.equals(migration.getVersionStream());
    }
}
