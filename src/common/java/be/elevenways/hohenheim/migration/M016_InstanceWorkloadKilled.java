package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Adds {@code instances.workload_killed_at}: when the status reconciler first observed the workload
 * killed (out of memory) inside a container the daemon still reports running.
 *
 * AIDEV-NOTE: nullable with no default and no backfill, so an upgraded install reads "no kill
 * observed" until the next sweep asks the daemon, never a fabricated verdict; the previous build
 * never reads the column, so it keeps working against the migrated table.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public class M016_InstanceWorkloadKilled extends HohenheimMigration {

    public M016_InstanceWorkloadKilled() {
        super("016", "Instance workload killed");
        // The stream chain (see HohenheimMigration): the previous migration of this stream.
        dependsOn(M015_InstallMediaFetches.class);
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("instances", table ->
            table.addColumn("workload_killed_at", ColumnType.DATETIME, column -> column.nullable(true)));
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.dropColumn("workload_killed_at"));
    }
}
