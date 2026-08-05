package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M009_CreateAccessLists extends HohenheimMigration {

    public M009_CreateAccessLists() {
        super("2026_04_02_000009", "Create access_lists table and link to sites");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("access_lists", table -> {
            table.id();
            table.string("name", 255);
            table.addColumn("satisfy", ColumnType.STRING, col -> col.maxLength(10).defaultValue("any"));
            table.addColumn("basic_auth_user", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("basic_auth_pass", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("allowed_ips", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("denied_ips", ColumnType.TEXT, col -> col.nullable(true));
            table.timestamps();
        });

        schema.alterTable("sites", table -> {
            table.addColumn("access_list_id", ColumnType.INTEGER, col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("sites", table -> table.dropColumn("access_list_id"));
        schema.dropTable("access_lists");
    }
}
