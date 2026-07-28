package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M023_AddDomainResponseHeaders extends Migration {

    public M023_AddDomainResponseHeaders() {
        super("2026_06_10_000023", "Add response header rules to site_domains");
    }

    @Override
    public void up(MigrationBuilder schema) {
        // Header rules applied to the OUTGOING response (custom_headers targets upstream requests).
        schema.alterTable("site_domains", table ->
            table.addColumn("response_headers", ColumnType.JSON,
                col -> col.nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("site_domains", table -> table.dropColumn("response_headers"));
    }
}
