package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M002_CreateOrganizations extends Migration {

    public M002_CreateOrganizations() {
        super("2026_03_31_000002", "Create organizations and members tables");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("organizations", table -> {
            table.id();
            table.string("name", 255);
            table.string("slug", 255);
            table.foreignId("owner_id", "users");
            table.timestamps();
            table.unique("slug");
        });

        schema.createTable("organization_members", table -> {
            table.id();
            table.foreignId("organization_id", "organizations");
            table.foreignId("user_id", "users");
            table.addColumn("role", ColumnType.STRING, col -> col.maxLength(50).defaultValue("member"));
            table.timestamps();
            table.unique("organization_id", "user_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("organization_members");
        schema.dropTable("organizations");
    }
}
