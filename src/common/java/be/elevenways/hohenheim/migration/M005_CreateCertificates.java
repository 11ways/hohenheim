package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M005_CreateCertificates extends HohenheimMigration {

    public M005_CreateCertificates() {
        super("2026_03_31_000005", "Create certificates table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("certificates", table -> {
            table.id();
            table.string("nice_name", 255);
            table.addColumn("provider", ColumnType.STRING, col -> col.maxLength(50).defaultValue("letsencrypt"));
            table.addColumn("certificate_pem", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("private_key_pem", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("expires_on", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("auto_renew", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.addColumn("dns_provider", ColumnType.STRING, col -> col.maxLength(100).nullable(true));
            table.addColumn("dns_credentials", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("acme_server", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("meta", ColumnType.JSON, col -> col.nullable(true));
            table.timestamps();
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("certificates");
    }
}
