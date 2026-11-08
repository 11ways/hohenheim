package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.zenit.test.support.TestSqliteFiles;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Builds the database an install that applied Hohenheim's migrations only up to one version is in.
 *
 * AIDEV-NOTE: an older install is built by migrating the DISCOVERED set whole, then rolling the Hohenheim migrations
 * above the version back through their own down() (a rollback removes its ledger row): the history a real install
 * that never applied them holds. Running a partial set instead is refused by core once a
 * migration above the cut is the stream's published version (a chain must hold its published migration), and
 * deleting a MIDDLE applied row produces a history no real install can have (a migration pending behind applied ones
 * of its own stream), which the strict integrity check rightly refuses as out of order. Other streams (zenit core,
 * the modules) stay whole: they number independently and are not what an upgrade of this app's jar moves.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstallsAt {

    private InstallsAt() {
    }

    /**
     * Migrate a new SQLite file at the path as far as an install that applied Hohenheim's stream
     * up to, but not including, {@code version}, closing the datasource afterwards.
     *
     * @throws IllegalStateException when that partial migration does not succeed
     */
    public static void migrateBelow(@NonNull Path file, @NonNull String version) {
        migrate(file, own -> own.compareTo(version) < 0);
    }

    /**
     * Migrate a new SQLite file at the path as far as an install that applied Hohenheim's stream
     * up to and including {@code version}, closing the datasource afterwards.
     *
     * @throws IllegalStateException when that partial migration does not succeed
     */
    public static void migrateThrough(@NonNull Path file, @NonNull String version) {
        migrate(file, own -> own.compareTo(version) <= 0);
    }

    private static void migrate(@NonNull Path file, @NonNull Predicate<String> keepsOwnVersion) {
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(file));
        try {
            new MigrationRunner(datasource).migrate().requireSuccess();
            List<MigrationKey> above = hohenheimMigrations(datasource.getDatasourceIdentifier()).stream()
                .filter(migration -> !keepsOwnVersion.test(migration.getVersion())).map(MigrationKey::of).toList();
            if (!above.isEmpty()) {
                new MigrationRunner(datasource).rollback(above).requireSuccess();
            }
        } finally {
            datasource.close();
        }
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
