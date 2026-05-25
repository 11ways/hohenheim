package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M015_CreateManagedDatabases extends Migration {

    public M015_CreateManagedDatabases() {
        super("2026_05_25_000015", "Create managed_databases table for the database manager");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("managed_databases", table -> {
            table.id();
            table.string("name", 128);
            table.string("engine", 32);
            table.addColumn("image", ColumnType.STRING, col -> col.maxLength(256).nullable(true));
            table.string("db_user", 128);
            table.string("db_password", 256);
            table.string("db_name", 128);
            table.addColumn("ephemeral", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.timestamps();
            table.unique("name");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("managed_databases");
    }
}
