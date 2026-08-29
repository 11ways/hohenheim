package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Drops {@code instance_templates.console_kind}: the console vocabulary moved into the
 * kind settings (where a template-less workspace or application can declare it too), and
 * the column was never read by anything.
 *
 * AIDEV-NOTE: {@code down} restores the column with its original shape so the install
 * schema rolls back cleanly under {@code MigrationIntegrityTest}; no data travels either
 * way because no row ever carried anything but the default.
 */
public class M003_TemplateConsoleKindDropped extends HohenheimMigration {

    public M003_TemplateConsoleKindDropped() {
        super("003", "Template console kind moved into kind settings");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("instance_templates", table -> table.dropColumn("console_kind"));
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("instance_templates", table ->
            table.addColumn("console_kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("plain")));
    }
}
