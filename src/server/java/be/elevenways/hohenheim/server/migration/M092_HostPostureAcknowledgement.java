package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The shared-container risk acknowledgement as COLUMNS on the host record: which posture
 * was accepted, which warning version, when, and by whom (id plus the label as it read).
 *
 * NO BACKFILL, deliberately. Auto-acknowledging the hosts already carrying
 * {@code shared_container} would forge an acknowledgement with no actor -- precisely what
 * the clause this migration exists for forbids. The named operational impact: such a host
 * stops accepting NEW tenant container placements until an operator acknowledges it. Same
 * choice and same reason as the kernel-truth gate -- a LIVE gate rather than a
 * migration-time cordon, so running workloads, stop and destroy stay untouched.
 *
 * @author Jelle De Loecker
 */
public class M092_HostPostureAcknowledgement extends HohenheimMigration {

    public M092_HostPostureAcknowledgement() {
        super("2026_09_08_100000", "Host posture acknowledgement");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("servers", table -> table.addColumn("acknowledged_posture",
            ColumnType.STRING, col -> col.maxLength(32).nullable(true).ifNotExists()));
        schema.alterTable("servers", table -> table.addColumn("acknowledged_warning_version",
            ColumnType.INTEGER, col -> col.nullable(true).ifNotExists()));
        schema.alterTable("servers", table -> table.addColumn("acknowledged_at",
            ColumnType.DATETIME, col -> col.nullable(true).ifNotExists()));
        schema.alterTable("servers", table -> table.addColumn("acknowledged_by",
            ColumnType.STRING, col -> col.maxLength(100).nullable(true).ifNotExists()));
        schema.alterTable("servers", table -> table.addColumn("acknowledged_by_label",
            ColumnType.STRING, col -> col.maxLength(200).nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("servers", table -> table.dropColumn("acknowledged_by_label"));
        schema.alterTable("servers", table -> table.dropColumn("acknowledged_by"));
        schema.alterTable("servers", table -> table.dropColumn("acknowledged_at"));
        schema.alterTable("servers", table -> table.dropColumn("acknowledged_warning_version"));
        schema.alterTable("servers", table -> table.dropColumn("acknowledged_posture"));
    }
}
