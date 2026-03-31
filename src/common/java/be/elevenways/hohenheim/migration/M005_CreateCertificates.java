package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M005_CreateCertificates extends Migration {

    public M005_CreateCertificates() {
        super("2026_03_31_000005", "Create certificates table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("certificates", table -> {
            table.id();
            table.foreignId("organization_id", "organizations");
            table.string("nice_name", 255);
            table.json("domain_names");
            table.addColumn("provider", ColumnType.STRING, col -> col.maxLength(50).defaultValue("letsencrypt"));
            table.text("certificate_pem");
            table.text("private_key_pem");
            table.datetime("expires_on");
            table.addColumn("auto_renew", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.string("dns_provider", 100);
            table.text("dns_credentials");
            table.string("acme_server", 255);
            table.json("meta");
            table.timestamps();
            table.addIndex("organization_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("certificates");
    }
}
