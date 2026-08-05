package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M011_CreateProclogs extends HohenheimMigration {

    public M011_CreateProclogs() {
        super("2026_04_02_000011", "Create proclogs table for process output");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("proclogs", table -> {
            table.id();
            table.foreignId("site_id", "sites");
            table.addColumn("pid", ColumnType.INTEGER, col -> {});
            table.addColumn("log_html", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("line_count", ColumnType.INTEGER, col -> col.defaultValue(0));
            table.datetime("created_at");
            table.addColumn("updated_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addIndex("site_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("proclogs");
    }
}
