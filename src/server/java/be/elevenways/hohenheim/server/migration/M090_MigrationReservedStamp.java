package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The migration window's reserved-amount stamp: what openMigrationWindow booked on the
 * destination host, released verbatim by both settle halves (see
 * InstanceModel.MIGRATE_RESERVED_MB for why a recomputed release corrupts the bucket).
 *
 * No heal: a row can only be mid-window while a controller is mid-migration, and the
 * settle's fallback for a stampless window is the old recompute -- one logged release,
 * after which every new window stamps.
 *
 * @author Jelle De Loecker
 */
public class M090_MigrationReservedStamp extends HohenheimMigration {

    public M090_MigrationReservedStamp() {
        super("2026_09_06_100000", "Migration reserved stamp");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.addColumn("migrate_reserved_mb",
            ColumnType.INTEGER, col -> col.nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.dropColumn("migrate_reserved_mb"));
    }
}
