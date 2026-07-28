package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M022_CreateSiteAuthProviders extends Migration {

    public M022_CreateSiteAuthProviders() {
        super("2026_06_04_000022", "Create site_auth_providers + site_sessions and link to sites");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("site_auth_providers", table -> {
            table.id();
            table.string("name", 255);
            table.addColumn("provider_type", ColumnType.STRING, col -> col.maxLength(50));
            table.addColumn("config", ColumnType.JSON, col -> col.nullable(true));
            table.addColumn("required_permission", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.timestamps();
        });

        schema.createTable("site_sessions", table -> {
            table.addColumn("id", ColumnType.STRING, col -> col.maxLength(64).primaryKey());
            table.addColumn("site_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("data", ColumnType.JSON, col -> col.nullable(true));
            table.datetime("created_at");
            table.datetime("expires_at");
            table.addIndex("site_id");
            table.addIndex("expires_at");
        });

        schema.alterTable("sites", table -> {
            table.addColumn("auth_provider_id", ColumnType.INTEGER,
                col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("sites", table -> table.dropColumn("auth_provider_id"));
        schema.dropTable("site_sessions");
        schema.dropTable("site_auth_providers");
    }
}
