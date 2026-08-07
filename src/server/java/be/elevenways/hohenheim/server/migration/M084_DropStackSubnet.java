package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Drops {@code stacks.subnet}, a CIDR the runtime never read.
 *
 * AIDEV-NOTE: the field promised "the stack's private network", but a lowered stack has
 * no single network any more: every service is an owned instance on its own per-workload
 * network plus a SHARED LINK network, and {@code LinkNetworkSupport.ensureLinkNetwork} /
 * {@code WorkloadNetworks.ensure} take no IPAM argument at all -- Docker pool-allocates
 * on purpose. Wiring it back would also let an operator author a range overlapping a
 * tenant subnet the nft policy denies. Same disposition, and the same paper-limit defect,
 * as {@code adopt_resources} in M083.
 *
 * @author Jelle De Loecker
 */
public class M084_DropStackSubnet extends HohenheimMigration {

    public M084_DropStackSubnet() {
        super("2026_08_31_100000", "Drop the unused stack subnet column");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("stacks", table -> table.dropColumn("subnet"));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("stacks", table -> table.addColumn("subnet",
            ColumnType.STRING, col -> col.nullable(true).ifNotExists()));
    }
}
