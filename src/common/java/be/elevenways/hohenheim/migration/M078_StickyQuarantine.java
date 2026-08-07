package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Give the QUARANTINE verdict its own columns, so a transient probe outcome can no longer
 * clear a trust verdict.
 *
 * AIDEV-NOTE: the backfill is not optional. Before this migration the verdict WAS
 * {@code last_error_kind = 'host_key_changed'}; deploying the new read without moving the
 * existing rows would silently un-quarantine every host that is quarantined right now,
 * which is the exact security regression this whole change is about. The timestamp is
 * copied from {@code updated_at} (the last write that touched the row, which for a
 * quarantined host is the quarantining write itself) rather than a literal, so no date
 * spelling has to be portable across the eight supported backends. The per-slot
 * {@code *_offered} marker needs no backfill: it is a column of its own already and
 * {@code HostPins.isQuarantined} keeps reading it.
 */
public class M078_StickyQuarantine extends HohenheimMigration {

    public M078_StickyQuarantine() {
        super("2026_08_25_100000", "Sticky host quarantine verdict");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("servers", table -> {
            table.addColumn("quarantined_at", ColumnType.DATETIME,
                column -> column.nullable(true).ifNotExists());
            table.addColumn("quarantine_reason", ColumnType.TEXT,
                column -> column.nullable(true).ifNotExists());
        });
        schema.execute("""
            UPDATE servers
               SET quarantined_at = updated_at,
                   quarantine_reason = last_error
             WHERE last_error_kind = 'host_key_changed'
            """);
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("servers", table -> {
            table.dropColumn("quarantine_reason");
            table.dropColumn("quarantined_at");
        });
    }
}
