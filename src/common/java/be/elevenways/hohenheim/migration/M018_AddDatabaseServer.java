package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M018_AddDatabaseServer extends HohenheimMigration {

    public M018_AddDatabaseServer() {
        super("2026_05_25_000018", "Add target server to managed_databases");
    }

    @Override
    public void up(MigrationBuilder schema) {
        // Which host (servers.name) the database container runs on; defaults to the local daemon.
        schema.alterTable("managed_databases", table ->
            table.addColumn("server_name", ColumnType.STRING,
                col -> col.maxLength(128).defaultValue("local").ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("managed_databases", table -> table.dropColumn("server_name"));
    }
}
