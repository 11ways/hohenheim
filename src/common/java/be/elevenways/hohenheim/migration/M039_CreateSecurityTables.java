package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Native security engine tables: reporters (hashed ingest tokens), aggregated
 * security events, IP bans, plus the per-site raw reporter token column used
 * for env injection into managed children.
 */
public class M039_CreateSecurityTables extends Migration {

    public M039_CreateSecurityTables() {
        super("2026_07_22_000039", "Create the security reporter, event, and ban tables");
    }

    @Override
    public void up(MigrationBuilder schema) {
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

        schema.createTable("bans", table -> {
            table.id();
            table.string("ip", 64);
            table.addColumn("reason", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.string("source", 100);
            table.addColumn("event_type", ColumnType.STRING, col -> col.maxLength(100).nullable(true));
            table.addColumn("expires_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("active", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.addColumn("lifted_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("lifted_by", ColumnType.STRING, col -> col.maxLength(200).nullable(true));
            table.timestamps();
            table.addIndex("ip");
            table.addIndex("active");
        });

        schema.alterTable("sites", table -> {
            table.addColumn("security_report_token", ColumnType.STRING,
                col -> col.maxLength(96).nullable(true));
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("sites", table -> table.dropColumn("security_report_token"));
        schema.dropTable("bans");
        schema.dropTable("security_events");
        schema.dropTable("security_reporters");
    }
}
