package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.ForeignKeyAction;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Creates the managed stack tables.
 *
 * The three table-stored SchemaField child tables are spelled out literally rather than
 * derived from StackServiceModel: a shipped migration must mean the same thing forever.
 */
public class M042_CreateStacks extends HohenheimMigration {

    // AIDEV-NOTE: This migration used to call `new StackServiceModel()` +
    // createSchemaTableFor(...), which derives its DDL from the model AS IT IS TODAY. Editing a
    // sub-schema (adding a mount field, say) therefore silently changed an ALREADY APPLIED
    // migration: existing installs kept the old columns, new installs got the new ones, and the
    // structural checksum moved under integrity checking. The literal DDL below is byte-identical
    // in effect to what the model produced on 2026-07-29: the structural checksum is unchanged,
    // pinned by MigrationIntegrityTest.m042StaysFrozen. A future sub-schema change adds a NEW
    // migration; this one never moves again.
    // Same caveat applies to createTranslationsTableFor and any other model-derived helper.

    public M042_CreateStacks() {
        super("2026_07_27_000042", "Create the managed stack tables");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("stacks", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING, col -> col.maxLength(100).unique());
            table.bool("enabled");
            table.addColumn("server_name", ColumnType.STRING, col -> col.maxLength(100).nullable(true));
            table.addColumn("subnet", ColumnType.STRING, col -> col.maxLength(50).nullable(true));
            table.bool("adopt_resources");
            table.addColumn("registry_server", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("registry_user", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            // Encrypted envelope, whatever the field's natural type.
            table.addColumn("registry_password", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("description", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("status", ColumnType.STRING, col -> col.maxLength(20).nullable(true));
            table.timestamps();
        });

        schema.createTable("stack_services", table -> {
            table.id();
            table.integer("stack_id");
            table.addColumn("name", ColumnType.STRING, col -> col.maxLength(100));
            table.bool("enabled");
            table.addColumn("image", ColumnType.STRING, col -> col.maxLength(255));
            table.json("command");
            table.json("environment");
            table.addColumn("health_cmd", ColumnType.STRING, col -> col.maxLength(500).nullable(true));
            table.integer("health_interval_seconds");
            table.integer("health_timeout_seconds");
            table.integer("health_retries");
            table.integer("health_start_period_seconds");
            table.addColumn("restart_policy", ColumnType.STRING, col -> col.maxLength(20).nullable(true));
            table.integer("memory_limit_mb");
            table.addColumn("cpu_limit", ColumnType.DOUBLE, col -> col.nullable(true));
            table.timestamps();
            table.addIndex("stack_id");
        });

        // Table-stored SchemaField child tables (StackServiceModel.MOUNTS / PORTS / DEPENDS_ON),
        // frozen at the shape createSchemaTableFor derived on 2026-07-29.
        schema.createTable("stack_services_mounts", table -> {
            table.id();
            table.addColumn("stack_service_id", ColumnType.INTEGER, col -> col
                .nullable(false)
                .references("stack_services", "id").onDelete(ForeignKeyAction.CASCADE));
            table.addColumn("order_key", ColumnType.LONG, col -> col.nullable(false));
            table.addColumn("type", ColumnType.STRING, col -> col.nullable());
            table.addColumn("name", ColumnType.STRING, col -> col.nullable());
            table.addColumn("container_path", ColumnType.STRING, col -> col.nullable());
            table.addColumn("external_name", ColumnType.STRING, col -> col.nullable());
            table.timestamps();
            table.addIndex("stack_service_id", "order_key");
        });

        schema.createTable("stack_services_ports", table -> {
            table.id();
            table.addColumn("stack_service_id", ColumnType.INTEGER, col -> col
                .nullable(false)
                .references("stack_services", "id").onDelete(ForeignKeyAction.CASCADE));
            table.addColumn("order_key", ColumnType.LONG, col -> col.nullable(false));
            table.addColumn("container_port", ColumnType.INTEGER, col -> col.nullable());
            table.addColumn("host_port", ColumnType.INTEGER, col -> col.nullable());
            table.addColumn("protocol", ColumnType.STRING, col -> col.nullable());
            table.addColumn("host_ip", ColumnType.STRING, col -> col.nullable());
            table.timestamps();
            table.addIndex("stack_service_id", "order_key");
        });

        schema.createTable("stack_services_depends_on", table -> {
            table.id();
            table.addColumn("stack_service_id", ColumnType.INTEGER, col -> col
                .nullable(false)
                .references("stack_services", "id").onDelete(ForeignKeyAction.CASCADE));
            table.addColumn("order_key", ColumnType.LONG, col -> col.nullable(false));
            table.addColumn("service", ColumnType.STRING, col -> col.nullable());
            table.addColumn("condition", ColumnType.STRING, col -> col.nullable());
            table.timestamps();
            table.addIndex("stack_service_id", "order_key");
        });

        schema.createTable("stack_files", table -> {
            table.id();
            table.integer("stack_service_id");
            table.addColumn("container_path", ColumnType.STRING, col -> col.maxLength(500));
            // Encrypted envelope (file contents routinely carry secrets).
            table.addColumn("content", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("mode", ColumnType.STRING, col -> col.maxLength(10).nullable(true));
            table.timestamps();
            table.addIndex("stack_service_id");
        });

        schema.createTable("stack_deployments", table -> {
            table.id();
            table.integer("stack_id");
            table.addColumn("status", ColumnType.STRING, col -> col.maxLength(20));
            table.addColumn("reason", ColumnType.STRING, col -> col.maxLength(50).nullable(true));
            table.addColumn("error", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("log", ColumnType.TEXT, col -> col.nullable(true));
            // Encrypted envelope: the snapshot embeds file contents and registry credentials.
            table.addColumn("spec", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("started_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("duration_ms", ColumnType.INTEGER, col -> col.nullable(true));
            table.timestamps();
            table.addIndex("stack_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("stack_services_depends_on");
        schema.dropTable("stack_services_ports");
        schema.dropTable("stack_services_mounts");
        schema.dropTable("stack_deployments");
        schema.dropTable("stack_files");
        schema.dropTable("stack_services");
        schema.dropTable("stacks");
    }
}
