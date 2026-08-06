package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;

/**
 * The durable identity of THIS controller, minted once into the same database that mints
 * the record ids every daemon resource name is derived from.
 *
 * AIDEV-NOTE: no backfill and no default value on purpose. A row is minted by
 * ControllerIdentity.resolve() on the first boot after this migration; a DEFAULT here
 * would hand every install the same token, which is precisely the shared-value failure
 * this table exists to make impossible. The table is single-row by construction (a
 * unique index on a constant column), so a restored control-plane backup carries its
 * controller's identity with it and keeps naming its own workloads.
 */
public class M077_ControllerIdentity extends HohenheimMigration {

    public M077_ControllerIdentity() {
        super("2026_08_22_100000", "Controller identity");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("controller_identity", table -> {
            table.id();
            // The only legal value of `singleton`, so the unique index below forbids a
            // second identity row in one database.
            table.addColumn("singleton", ColumnType.INTEGER, column -> column.defaultValue(1));
            table.string("token", 32);
            table.timestamps();
            table.unique("controller_identity_singleton_unique", List.of("singleton"));
            table.unique("controller_identity_token_unique", List.of("token"));
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("controller_identity");
    }
}
