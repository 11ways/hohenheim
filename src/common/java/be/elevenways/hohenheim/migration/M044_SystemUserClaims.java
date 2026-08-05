package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Records workload-identity ownership on system users: {@code site_id} is the claim
 * (which site runs as this uid), {@code managed} marks rows Hohenheim provisioned so
 * the /etc/passwd reconciler never re-classifies them. The unique index makes a claim
 * exclusive at the database level; SQLite treats NULLs as distinct, so unclaimed rows
 * coexist freely.
 */
public class M044_SystemUserClaims extends HohenheimMigration {

    public M044_SystemUserClaims() {
        super("2026_07_29_000044", "Record per-site system user claims");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("system_users", table -> {
            table.addColumn("site_id", ColumnType.INTEGER, col -> col.nullable(true).ifNotExists());
            table.addColumn("managed", ColumnType.BOOLEAN, col -> col.defaultValue(false).ifNotExists());
            table.unique("site_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("system_users", table -> {
            table.dropIndex("system_users_site_id_unique");
            table.dropColumn("managed");
            table.dropColumn("site_id");
        });
    }
}
