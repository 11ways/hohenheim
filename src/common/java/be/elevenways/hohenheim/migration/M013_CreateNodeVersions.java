package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M013_CreateNodeVersions extends Migration {

    public M013_CreateNodeVersions() {
        super("2026_04_09_000013", "Create node_versions table for per-site node binary selection");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("node_versions", table -> {
            table.id();
            table.string("version", 64);
            table.string("path", 512);
            table.addColumn("source", ColumnType.STRING, col -> col.maxLength(64).nullable(true));
            table.addColumn("obsolete", ColumnType.BOOLEAN, col -> col.defaultValue(false));
            table.datetime("last_seen_at");
            table.timestamps();
            table.unique("path");
            table.addIndex("obsolete");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("node_versions");
    }
}
