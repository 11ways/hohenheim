package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Per-record dynamic-DNS flag and hashed update-token material (dyndns2).
 */
public class M038_AddDynamicDns extends HohenheimMigration {

    public M038_AddDynamicDns() {
        super("2026_07_17_000038", "Add dynamic-DNS token material to records");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("dns_records", table -> {
            table.addColumn("dynamic", ColumnType.BOOLEAN, col -> col.defaultValue(false).ifNotExists());
            table.addColumn("dyndns_token", ColumnType.STRING,
                col -> col.maxLength(96).nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("dns_records", table -> {
            table.dropColumn("dynamic");
            table.dropColumn("dyndns_token");
        });
    }
}
