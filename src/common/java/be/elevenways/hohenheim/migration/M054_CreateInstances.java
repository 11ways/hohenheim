package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The instance tier's record: one managed runtime unit per row. No owner column
 * (ownership is grant-derived) and no fence/generation columns (nothing to fence
 * against exists yet) -- both by decision, see docs/instance-tier-plan.md Phase 3.
 */
public class M054_CreateInstances extends HohenheimMigration {

    public M054_CreateInstances() {
        super("2026_08_03_000154", "Create instances table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("instances", table -> {
            table.id();
            table.string("name", 255);
            table.string("kind", 100);
            table.addColumn("settings", ColumnType.JSON, col -> col.nullable(true));
            table.addColumn("server_id", ColumnType.INTEGER,
                col -> col.nullable(true).references("servers", "id"));
            table.addColumn("status", ColumnType.STRING,
                col -> col.maxLength(50).defaultValue("created"));
            table.timestamps();
            table.softDeletes();
            table.addIndex("server_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("instances");
    }
}
