package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Adds the federation health bookkeeping: per secondary link the serial the peer was seen
 * serving (with probe time, error, lag start and alert stamp), and per zone the verdict of
 * the last delegation check against the parent zone.
 *
 * AIDEV-NOTE: every column is nullable with no default, so an upgraded install reads
 * "never probed" and "never checked" until the tasks run, never a fabricated healthy state.
 */
public class M004_DnsFederationHealth extends HohenheimMigration {

    public M004_DnsFederationHealth() {
        super("004", "DNS federation health");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("dns_zone_peers", table -> {
            table.addColumn("served_serial", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("probed_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("probe_error", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("behind_since", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("stale_alerted_at", ColumnType.DATETIME, column -> column.nullable(true));
        });
        schema.alterTable("dns_zones", table -> {
            table.addColumn("delegation_status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32));
            table.addColumn("delegation_detail", ColumnType.TEXT, column -> column.nullable(true));
            table.addColumn("delegation_checked_at", ColumnType.DATETIME,
                column -> column.nullable(true));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("dns_zones", table -> {
            table.dropColumn("delegation_checked_at");
            table.dropColumn("delegation_detail");
            table.dropColumn("delegation_status");
        });
        schema.alterTable("dns_zone_peers", table -> {
            table.dropColumn("stale_alerted_at");
            table.dropColumn("behind_since");
            table.dropColumn("probe_error");
            table.dropColumn("probed_at");
            table.dropColumn("served_serial");
        });
    }
}
