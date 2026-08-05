package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M028_CreateDeployments extends HohenheimMigration {

    public M028_CreateDeployments() {
        super("2026_07_08_000028", "Create the deployments history table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("deployments", table -> {
            table.id();
            table.integer("site_id");
            table.addColumn("status", ColumnType.STRING, col -> col.maxLength(20));
            table.addColumn("reason", ColumnType.STRING, col -> col.maxLength(50).nullable(true));
            table.addColumn("commit_sha", ColumnType.STRING, col -> col.maxLength(64).nullable(true));
            table.addColumn("slot", ColumnType.STRING, col -> col.maxLength(10).nullable(true));
            table.addColumn("error", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("log", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("started_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("duration_ms", ColumnType.INTEGER, col -> col.nullable(true));
            table.timestamps();
            table.addIndex("site_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("deployments");
    }
}
