package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M029_AddDatabaseLimits extends Migration {

    public M029_AddDatabaseLimits() {
        super("2026_07_08_000029", "Add resource-limit columns to managed_databases");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("managed_databases", table -> {
            table.addColumn("memory_limit_mb", ColumnType.INTEGER, col -> col.nullable(true).ifNotExists());
            table.addColumn("cpu_limit", ColumnType.DOUBLE, col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("managed_databases", table -> {
            table.dropColumn("memory_limit_mb");
            table.dropColumn("cpu_limit");
        });
    }
}
