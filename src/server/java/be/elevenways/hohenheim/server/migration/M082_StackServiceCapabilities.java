package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The per-service capability declaration: which Linux capabilities a stack service's
 * IMAGE needs on top of the tier's drop-ALL-plus-SERVICE baseline.
 *
 * Column-only and NULL for everything that exists, which is the correct starting state --
 * every service deployed before this ran with the baseline and nothing else, so an empty
 * declaration is not a default standing in for an unknown, it is the fact.
 *
 * @author Jelle De Loecker
 */
public class M082_StackServiceCapabilities extends HohenheimMigration {

    public M082_StackServiceCapabilities() {
        super("2026_08_29_100000", "Stack service capability declaration");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("stack_services", table -> table.addColumn("capabilities",
            ColumnType.JSON, col -> col.nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("stack_services", table -> table.dropColumn("capabilities"));
    }
}
