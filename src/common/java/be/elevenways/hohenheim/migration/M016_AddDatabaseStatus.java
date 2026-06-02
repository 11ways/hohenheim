package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M016_AddDatabaseStatus extends Migration {

    public M016_AddDatabaseStatus() {
        super("2026_05_25_000016", "Add provisioning status to managed_databases");
    }

    @Override
    public void up(MigrationBuilder schema) {
        // provisioning -> active | failed; defaults to active for records created synchronously.
        schema.alterTable("managed_databases", table ->
            table.addColumn("status", ColumnType.STRING, col -> col.maxLength(20).defaultValue("active")));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("managed_databases", table -> table.dropColumn("status"));
    }
}
