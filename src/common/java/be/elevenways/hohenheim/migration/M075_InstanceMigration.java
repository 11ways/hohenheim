package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Cold migration between hosts: the destination pointer an interrupted migration is
 * settled from (see InstanceModel.MIGRATE_TARGET_ID for the ownership discipline).
 */
public class M075_InstanceMigration extends HohenheimMigration {

    public M075_InstanceMigration() {
        super("2026_08_20_100000", "Instance cold migration");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.addColumn("migrate_target_id",
            ColumnType.INTEGER, column -> column.nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.dropColumn("migrate_target_id"));
    }
}
