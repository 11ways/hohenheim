package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M001_CreateUsers extends Migration {

    public M001_CreateUsers() {
        super("2026_03_31_000001", "Create users table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("users", table -> {
            table.id();
            table.string("email", 255);
            table.string("name", 255);
            table.string("password_hash", 255);
            table.string("totp_secret", 255);
            table.addColumn("totp_enabled", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.json("backup_codes");
            table.addColumn("is_disabled", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.addColumn("force_password_reset", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.datetime("email_verified_at");
            table.timestamps();
            table.softDeletes();
            table.unique("email");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("users");
    }
}
