package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Adds {@code dns_zone_peers.last_notify_serial}, the serial the last NOTIFY announced,
 * so a NOTIFY is as diagnosable from this side as the AXFR it triggers.
 *
 * AIDEV-NOTE: nullable with no default, so links stamped before this reads "unknown"
 * rather than claiming serial 0 was ever announced.
 */
public class M007_DnsNotifySerial extends HohenheimMigration {

    public M007_DnsNotifySerial() {
        super("007", "DNS NOTIFY serial trace");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("dns_zone_peers", table ->
            table.addColumn("last_notify_serial", ColumnType.INTEGER, column -> column.nullable(true)));
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("dns_zone_peers", table -> table.dropColumn("last_notify_serial"));
    }
}
