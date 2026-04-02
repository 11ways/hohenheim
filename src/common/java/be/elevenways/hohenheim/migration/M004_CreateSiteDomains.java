package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M004_CreateSiteDomains extends Migration {

    public M004_CreateSiteDomains() {
        super("2026_03_31_000004", "Create site_domains table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("site_domains", table -> {
            table.id();
            table.foreignId("site_id", "sites");
            table.string("hostname", 255);
            table.addColumn("match_type", ColumnType.STRING, col -> col.maxLength(20).defaultValue("exact"));
            table.addColumn("listen_on", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("port", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("path", ColumnType.STRING, col -> col.maxLength(512).nullable(true));
            table.addColumn("strip_path", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.addColumn("force_ssl", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.addColumn("certificate_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("certificate_type", ColumnType.STRING, col -> col.maxLength(20).nullable(true));
            table.addColumn("hsts_enabled", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.addColumn("hsts_subdomains", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.addColumn("http2_support", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.addColumn("custom_headers", ColumnType.JSON, col -> col.nullable(true));
            table.timestamps();
            table.addIndex("site_id");
            table.addIndex("hostname");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("site_domains");
    }
}
