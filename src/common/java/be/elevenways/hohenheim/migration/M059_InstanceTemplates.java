package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Phase 5's template foundation: instance templates (kind+settings baseline, typed
 * variable schema, config files, install step, reinstall policy, approval + import
 * provenance), per-instance variable VALUES (encrypted secret carrier) and per-instance
 * config files, plus the instance columns the durable install lifecycle needs.
 */
public class M059_InstanceTemplates extends HohenheimMigration {

    public M059_InstanceTemplates() {
        super("2026_08_03_180000", "Instance templates, typed variables and config files");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("instance_templates", table -> {
            table.id();
            table.string("name", 255);
            table.addColumn("description", ColumnType.TEXT, col -> col.nullable(true));
            table.string("kind", 100);
            table.addColumn("settings", ColumnType.JSON, col -> col.nullable(true));
            table.addColumn("version", ColumnType.INTEGER, col -> col.defaultValue(1));
            table.addColumn("install_image", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("install_script", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("reinstall_policy", ColumnType.STRING,
                col -> col.maxLength(50).defaultValue("preserve"));
            table.addColumn("readiness_line", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("stop_command", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("approved_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("approved_by_user_id", ColumnType.LONG, col -> col.nullable(true));
            table.addColumn("source", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("source_checksum", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("imported_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.timestamps();
        });

        schema.createTable("instance_template_variables", table -> {
            table.id();
            table.addColumn("template_id", ColumnType.INTEGER,
                col -> col.references("instance_templates", "id"));
            table.string("key", 191);
            table.addColumn("label", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("description", ColumnType.STRING, col -> col.nullable(true));
            table.string("type", 100);
            table.addColumn("settings", ColumnType.JSON, col -> col.nullable(true));
            table.addColumn("required", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.addColumn("default_value", ColumnType.STRING, col -> col.nullable(true));
            table.timestamps();
            table.addIndex("template_id");
        });

        schema.createTable("instance_template_files", table -> {
            table.id();
            table.addColumn("template_id", ColumnType.INTEGER,
                col -> col.references("instance_templates", "id"));
            table.string("container_path", 512);
            table.addColumn("content", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("mode", ColumnType.STRING,
                col -> col.maxLength(10).defaultValue("0644"));
            table.timestamps();
            table.addIndex("template_id");
        });

        schema.createTable("instance_variables", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                col -> col.references("instances", "id"));
            table.string("key", 191);
            table.addColumn("kind", ColumnType.STRING,
                col -> col.maxLength(50).defaultValue("plain"));
            table.addColumn("plain_value", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("secret_value", ColumnType.TEXT, col -> col.nullable(true));
            table.timestamps();
            table.addIndex("instance_id");
        });

        schema.createTable("instance_files", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                col -> col.references("instances", "id"));
            table.string("container_path", 512);
            table.addColumn("content", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("mode", ColumnType.STRING,
                col -> col.maxLength(10).defaultValue("0644"));
            table.timestamps();
            table.addIndex("instance_id");
        });

        schema.alterTable("instances", table -> {
            table.addColumn("template_id", ColumnType.INTEGER,
                col -> col.nullable(true).references("instance_templates", "id").ifNotExists());
            table.addColumn("install_state", ColumnType.STRING,
                col -> col.maxLength(50).defaultValue("none").ifNotExists());
            table.addColumn("install_error", ColumnType.TEXT,
                col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> {
            table.dropColumn("install_error");
            table.dropColumn("install_state");
            table.dropColumn("template_id");
        });
        schema.dropTable("instance_files");
        schema.dropTable("instance_variables");
        schema.dropTable("instance_template_files");
        schema.dropTable("instance_template_variables");
        schema.dropTable("instance_templates");
    }
}
