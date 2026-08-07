package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Drops {@code stacks.adopt_resources}, which the Phase 7 stack lowering left with
 * nothing to mean.
 *
 * AIDEV-NOTE: it used to say "replace a same-named container, network or volume that
 * carries no ownership label of ours". A lowered stack owns none of those by name any
 * more: its containers are instance-id-keyed and adopted only through the instance tier's
 * {@code removeIfOwnedBy} (which refuses an unattributable container by design and has no
 * opt-out), and its networks are link networks. Adopting a pre-existing VOLUME is still
 * expressible, per mount, by {@code stack_services.mounts[].external_name}. A settings
 * checkbox that silently does nothing is the paper-limit defect, so it goes rather than
 * being left to reassure an operator about a behaviour that no longer exists.
 *
 * @author Jelle De Loecker
 */
public class M083_DropStackAdoptResources extends HohenheimMigration {

    public M083_DropStackAdoptResources() {
        super("2026_08_30_100000", "Drop the stack adopt-resources flag");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("stacks", table -> table.dropColumn("adopt_resources"));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("stacks", table -> table.addColumn("adopt_resources",
            ColumnType.BOOLEAN, col -> col.nullable(true).ifNotExists()));
    }
}
