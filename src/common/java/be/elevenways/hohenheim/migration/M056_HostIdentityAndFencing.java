package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Grows the host record into something an allocator can trust (posture, admission,
 * probed capabilities, typed health) and adds the fence columns runtime-outcome
 * writes are conditional on.
 *
 * AIDEV-NOTE: admission DEFAULTS TO {@code blocked} for every row, including hosts
 * enrolled before preflight existed -- a host with no successful probe is never
 * silently trusted. Nothing is live, so no heal admits existing hosts.
 */
public class M056_HostIdentityAndFencing extends HohenheimMigration {

    public M056_HostIdentityAndFencing() {
        super("2026_08_03_140000", "Host identity, admission and fencing columns");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("servers", table -> {
            table.addColumn("posture", ColumnType.STRING,
                col -> col.maxLength(32).defaultValue("trusted_only").ifNotExists());
            table.addColumn("admission", ColumnType.STRING,
                col -> col.maxLength(32).defaultValue("blocked").ifNotExists());
            table.addColumn("capabilities", ColumnType.JSON, col -> col.nullable(true).ifNotExists());
            table.addColumn("probed_at", ColumnType.DATETIME, col -> col.nullable(true).ifNotExists());
            table.addColumn("preflight_ok", ColumnType.BOOLEAN, col -> col.defaultValue(false).ifNotExists());
            table.addColumn("last_seen_at", ColumnType.DATETIME, col -> col.nullable(true).ifNotExists());
            table.addColumn("last_error_kind", ColumnType.STRING,
                col -> col.maxLength(32).nullable(true).ifNotExists());
            table.addColumn("last_error", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("host_key_fingerprint", ColumnType.STRING,
                col -> col.maxLength(255).nullable(true).ifNotExists());
            table.addColumn("controller_version", ColumnType.STRING,
                col -> col.maxLength(64).nullable(true).ifNotExists());
        });
        schema.alterTable("instances", table -> {
            table.addColumn("claim_fence", ColumnType.LONG, col -> col.nullable(true).ifNotExists());
        });
        schema.alterTable("port_allocations", table -> {
            table.addColumn("controller_fence", ColumnType.LONG, col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("port_allocations", table -> table.dropColumn("controller_fence"));
        schema.alterTable("instances", table -> table.dropColumn("claim_fence"));
        schema.alterTable("servers", table -> {
            table.dropColumn("controller_version");
            table.dropColumn("host_key_fingerprint");
            table.dropColumn("last_error");
            table.dropColumn("last_error_kind");
            table.dropColumn("last_seen_at");
            table.dropColumn("preflight_ok");
            table.dropColumn("probed_at");
            table.dropColumn("capabilities");
            table.dropColumn("admission");
            table.dropColumn("posture");
        });
    }
}
