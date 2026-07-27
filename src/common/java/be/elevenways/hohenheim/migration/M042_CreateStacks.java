package be.elevenways.hohenheim.migration;

import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M042_CreateStacks extends Migration {

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

        StackServiceModel services = new StackServiceModel();
        schema.createSchemaTableFor(services, "mounts");
        schema.createSchemaTableFor(services, "ports");
        schema.createSchemaTableFor(services, "depends_on");

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
        StackServiceModel services = new StackServiceModel();
        schema.dropSchemaTableFor(services, "depends_on");
        schema.dropSchemaTableFor(services, "ports");
        schema.dropSchemaTableFor(services, "mounts");
        schema.dropTable("stack_deployments");
        schema.dropTable("stack_files");
        schema.dropTable("stack_services");
        schema.dropTable("stacks");
    }
}
