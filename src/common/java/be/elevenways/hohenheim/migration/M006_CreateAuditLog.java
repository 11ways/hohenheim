package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
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
            table.addColumn("organization_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("user_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("user_email", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("user_role", ColumnType.STRING, col -> col.maxLength(50).nullable(true));
            table.string("action", 50);
            table.string("resource_type", 100);
            table.addColumn("resource_id", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("resource_name", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("metadata", ColumnType.JSON, col -> col.nullable(true));
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
