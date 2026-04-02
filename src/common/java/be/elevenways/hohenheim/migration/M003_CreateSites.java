package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M003_CreateSites extends Migration {

    public M003_CreateSites() {
        super("2026_03_31_000003", "Create sites table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("sites", table -> {
            table.id();
            table.string("name", 255);
            table.string("slug", 255);
            table.string("site_type", 50);
            table.addColumn("enabled", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.addColumn("organization_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("settings", ColumnType.JSON, col -> col.nullable(true));
            table.addColumn("description", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("status", ColumnType.STRING, col -> col.maxLength(50).defaultValue("idle"));
            table.timestamps();
            table.softDeletes();
            table.unique("slug");
            table.addIndex("site_type");
            table.addIndex("organization_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("sites");
    }
}
