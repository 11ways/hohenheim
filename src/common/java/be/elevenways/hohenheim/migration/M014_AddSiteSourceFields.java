package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M014_AddSiteSourceFields extends HohenheimMigration {

    public M014_AddSiteSourceFields() {
        super("2026_04_10_000014", "Add source and source_settings to sites");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("sites", table -> {
            table.addColumn("source", ColumnType.STRING,
                col -> col.maxLength(20).nullable(true).ifNotExists());
            table.addColumn("source_settings", ColumnType.JSON, col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("sites", table -> {
            table.dropColumn("source");
            table.dropColumn("source_settings");
        });
    }
}
