package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Moves the app-private audit_log rows into the framework activity log
 * (zenit_activity, created by zenit's own M001) and drops the old table.
 * Resource-type tokens map to model identifiers, legacy action verbs to the
 * activity vocabulary; entries keep their original timestamps and actors.
 *
 * @throws UnsupportedOperationException from {@link #down} — the copied rows
 *         blend into zenit_activity and cannot be carved back out.
 */
public class M026_MigrateAuditLogToActivity extends HohenheimMigration {

    public M026_MigrateAuditLogToActivity() {
        super("2026_07_07_000026", "Move audit_log into the framework activity log");
    }

    // AIDEV-NOTE: This migration is NOT re-run safe and cannot be made so. The copy is an
    // INSERT ... SELECT FROM audit_log with no row identity to dedup on, and the final DROP TABLE
    // means a second run fails at the first ALTER with "no such table: audit_log" -- a loud,
    // correct refusal. Adding IF EXISTS to the DROP would only move the recorded checksum without
    // ever being reached. Left as-is deliberately during the 2026-07-29 integrity pass.

    @Override
    public void up(MigrationBuilder schema) {
        // Installs that predate the migration system carry the original audit_log
        // shape (user_email, no user_label). Normalize both shapes first so the
        // one copy statement below parses everywhere.
        schema.alterTable("audit_log", table -> {
            table.addColumn("user_label", ColumnType.STRING,
                col -> col.maxLength(255).nullable(true).ifNotExists());
            table.addColumn("user_email", ColumnType.STRING,
                col -> col.maxLength(255).nullable(true).ifNotExists());
        });

        schema.execute("""
            INSERT INTO zenit_activity
                (model, record_id, action, actor, actor_label, origin, detail, created_at)
            SELECT
                'hohenheim:' || CASE resource_type
                    WHEN 'domain' THEN 'site_domain'
                    WHEN 'auth_provider' THEN 'site_auth_provider'
                    ELSE resource_type END,
                coalesce(resource_id, ''),
                CASE action
                    WHEN 'created' THEN 'create'
                    WHEN 'updated' THEN 'update'
                    WHEN 'deleted' THEN 'delete'
                    WHEN 'restored' THEN 'restore'
                    ELSE action END,
                CASE WHEN user_id IS NULL THEN NULL ELSE CAST(user_id AS TEXT) END,
                coalesce(user_label, user_email),
                'web',
                resource_name,
                created_at
            FROM audit_log""");

        schema.execute("DROP TABLE audit_log");
    }

    @Override
    public void down(MigrationBuilder schema) {
        throw new UnsupportedOperationException(
            "audit_log rows were merged into zenit_activity and cannot be split back out");
    }
}
