package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M006_CreateAuditLog extends Migration {

    public M006_CreateAuditLog() {
        super("2026_03_31_000006", "Create audit_log table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("audit_log", table -> {
            table.id();
            table.foreignId("organization_id", "organizations");
            table.foreignId("user_id", "users");
            table.string("user_email", 255);
            table.string("user_role", 50);
            table.string("action", 50);
            table.string("resource_type", 100);
            table.string("resource_id", 255);
            table.string("resource_name", 255);
            table.json("metadata");
            table.datetime("created_at");
            table.addIndex("organization_id");
            table.addIndex("resource_type", "resource_id");
            table.addIndex("created_at");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("audit_log");
    }
}
