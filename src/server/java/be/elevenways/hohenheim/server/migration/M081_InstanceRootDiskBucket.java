package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The root-disk knob's ledger stamp: the owner disk-GB bucket an instance's ROOT disk
 * reservation was charged to.
 *
 * Deliberately column-only, with NO heal. Nothing that exists before this migration
 * declares a root disk -- the setting did not exist -- so there is no outstanding charge
 * to seed and no bucket to stamp. A backfill here would have to invent both, and an
 * invented reservation is worse than an absent one.
 *
 * @author Jelle De Loecker
 */
public class M081_InstanceRootDiskBucket extends HohenheimMigration {

    public M081_InstanceRootDiskBucket() {
        super("2026_08_28_100000", "Instance root disk quota bucket");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.addColumn("root_disk_bucket",
            ColumnType.STRING, col -> col.nullable(true).maxLength(191).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.dropColumn("root_disk_bucket"));
    }
}
