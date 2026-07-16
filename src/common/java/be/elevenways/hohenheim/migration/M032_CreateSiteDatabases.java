package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M032_CreateSiteDatabases extends Migration {

    public M032_CreateSiteDatabases() {
        super("2026_07_16_000032", "Create the site-database attachment table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("site_databases", table -> {
            table.id();
            table.integer("site_id");
            table.integer("database_id");
            table.addColumn("env_prefix", ColumnType.STRING, col -> col.maxLength(40));
            table.timestamps();
            table.addIndex("site_id");
            table.addIndex("database_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("site_databases");
    }
}
