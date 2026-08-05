package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Spamservice is now the single security-event authority: the local ingest
 * store and reporter registry are gone. The sites.security_report_token
 * column STAYS (it now holds the site's raw spamservice client key).
 */
public class M040_DropSecurityIngestTables extends HohenheimMigration {

    public M040_DropSecurityIngestTables() {
        super("2026_07_22_000040", "Drop the security event and reporter tables");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.dropTable("security_events");
        schema.dropTable("security_reporters");
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.createTable("security_reporters", table -> {
            table.id();
            table.string("name", 200);
            table.addColumn("site_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.string("token_hash", 96);
            table.addColumn("enabled", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.addColumn("last_seen_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.timestamps();
            table.addIndex("token_hash");
            table.addIndex("site_id");
        });

        schema.createTable("security_events", table -> {
            table.id();
            table.addColumn("reporter_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.string("type", 100);
            table.string("ip", 64);
            table.addColumn("day", ColumnType.DATE);
            table.addColumn("count", ColumnType.INTEGER, col -> col.defaultValue(0));
            table.datetime("first_at");
            table.datetime("last_at");
            table.addColumn("last_detail", ColumnType.STRING, col -> col.maxLength(400).nullable(true));
            table.timestamps();
            table.addIndex("type", "ip", "day");
            table.addIndex("day");
        });
    }
}
