package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Adds {@code instance_template_databases}: the managed databases a template declares it
 * needs, allocated and attached when an instance is created from it.
 *
 * AIDEV-NOTE: numbered 006 because 004 (DNS freshness) and 005 (sites API) were reserved
 * by concurrent work the day this landed; the runner orders by version string, so a gap
 * that never fills is harmless.
 */
public class M006_TemplateDatabases extends HohenheimMigration {

    public M006_TemplateDatabases() {
        super("006", "Template-declared managed databases");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.createTable("instance_template_databases", table -> {
            table.id();
            table.addColumn("template_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("instance_templates", "id"));
            table.addColumn("engine", ColumnType.STRING,
                column -> column.nullable(false).maxLength(32));
            table.addColumn("env_prefix", ColumnType.STRING,
                column -> column.nullable(false).maxLength(64));
            table.addColumn("image", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.timestamps();
            table.unique("instance_template_databases_unique", List.of("template_id", "env_prefix"));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.dropTable("instance_template_databases");
    }
}
