package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The OBSERVED root-disk figures of an instance, stamped by the disk sweeper so the
 * dashboard reads a stored fact instead of probing every daemon per render.
 *
 * AIDEV-NOTE: nullable on purpose and forever. A tier whose driver cannot measure root
 * disk (Docker) leaves these NULL, and null means "not measured" -- distinct from a zero,
 * which would read as "empty". The attention collector treats null as no news.
 *
 * @author Jelle De Loecker
 */
public class M086_InstanceDiskObservation extends HohenheimMigration {

    public M086_InstanceDiskObservation() {
        super("2026_09_02_100000", "Observed instance disk usage");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instances", table -> {
            table.addColumn("disk_used_bytes", ColumnType.LONG,
                col -> col.nullable(true).ifNotExists());
            table.addColumn("disk_limit_bytes", ColumnType.LONG,
                col -> col.nullable(true).ifNotExists());
            table.addColumn("disk_observed_at", ColumnType.DATETIME,
                col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> {
            table.dropColumn("disk_used_bytes");
            table.dropColumn("disk_limit_bytes");
            table.dropColumn("disk_observed_at");
        });
    }
}
