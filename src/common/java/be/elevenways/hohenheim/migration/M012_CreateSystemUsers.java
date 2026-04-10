package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M012_CreateSystemUsers extends Migration {

    public M012_CreateSystemUsers() {
        super("2026_04_09_000012", "Create system_users table for per-site uid selection");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("system_users", table -> {
            table.id();
            table.string("name", 255);
            table.addColumn("uid", ColumnType.INTEGER, col -> {});
            table.addColumn("gid", ColumnType.INTEGER, col -> {});
            table.addColumn("home", ColumnType.STRING, col -> col.maxLength(512).nullable(true));
            table.addColumn("gecos", ColumnType.STRING, col -> col.maxLength(512).nullable(true));
            table.addColumn("obsolete", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.datetime("last_seen_at");
            table.timestamps();
            table.unique("name");
            table.addIndex("obsolete");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("system_users");
    }
}
