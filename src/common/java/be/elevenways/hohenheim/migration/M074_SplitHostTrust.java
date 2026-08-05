package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Give the Incus TLS trust relationship its own columns, so {@code host_key} and
 * {@code identity_*} mean ONE thing (ssh) again and an Incus host can hold an ssh admin
 * lane at the same time as its daemon certificate.
 *
 * AIDEV-NOTE: what happens to EXISTING incus rows. The pin half moves losslessly here
 * (server certificate, fingerprint, verified flag, pinned_at, offer) -- those columns are
 * plaintext, so the values carry over and no host has to be re-confirmed. The CLIENT
 * half does not: {@code identity_private_key} is an {@code encrypted()} column and zenit
 * binds table+column into the AES-GCM envelope's additional authenticated data (v2), so a
 * copied envelope would not decrypt in its new column -- by design, since that binding is
 * what stops an attacker with write access from grafting a secret between columns. The
 * incus client certificate is therefore CLEARED for those rows rather than moved, and the
 * host lands on the typed {@code NO_IDENTITY} refusal ("rotate the identity and enroll it
 * on the daemon"), whose recovery is the existing two-click ceremony: rotate identity,
 * paste a trust token. That is a loud, named, documented state -- not a silent one -- and
 * it costs at most the small set of https Incus hosts that existed before this migration,
 * against re-encrypting secrets inside a schema migration for every row of a table.
 * Docker/ssh rows are not touched at all: they keep the columns they always used.
 */
public class M074_SplitHostTrust extends HohenheimMigration {

    public M074_SplitHostTrust() {
        super("2026_08_14_100000", "Split host trust into ssh and incus slots");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("servers", table -> {
            table.addColumn("incus_server_cert", ColumnType.TEXT,
                column -> column.nullable(true).ifNotExists());
            table.addColumn("incus_server_cert_fingerprint", ColumnType.STRING,
                column -> column.maxLength(191).nullable(true).ifNotExists());
            table.addColumn("incus_server_cert_verified", ColumnType.BOOLEAN,
                column -> column.nullable(false).defaultValue(false).ifNotExists());
            table.addColumn("incus_server_cert_pinned_at", ColumnType.DATETIME,
                column -> column.nullable(true).ifNotExists());
            table.addColumn("incus_server_cert_offered", ColumnType.TEXT,
                column -> column.nullable(true).ifNotExists());
            table.addColumn("incus_client_cert", ColumnType.TEXT,
                column -> column.nullable(true).ifNotExists());
            table.addColumn("incus_client_key", ColumnType.TEXT,
                column -> column.nullable(true).ifNotExists());
        });

        // MOVE the pin: an incus host's ssh columns held its daemon certificate, and a
        // certificate left in the known_hosts slot is exactly the confusion this splits.
        schema.execute("""
            UPDATE servers
               SET incus_server_cert = host_key,
                   incus_server_cert_fingerprint = host_key_fingerprint,
                   incus_server_cert_verified = host_key_verified,
                   incus_server_cert_pinned_at = host_key_pinned_at,
                   incus_server_cert_offered = host_key_offered,
                   incus_client_cert = identity_public_key
             WHERE runtime = 'incus'
            """);
        schema.execute("""
            UPDATE servers
               SET host_key = NULL,
                   host_key_fingerprint = NULL,
                   host_key_verified = 0,
                   host_key_pinned_at = NULL,
                   host_key_offered = NULL,
                   identity_public_key = NULL,
                   identity_private_key = NULL
             WHERE runtime = 'incus'
            """);
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.execute("""
            UPDATE servers
               SET host_key = incus_server_cert,
                   host_key_fingerprint = incus_server_cert_fingerprint,
                   host_key_verified = incus_server_cert_verified,
                   host_key_pinned_at = incus_server_cert_pinned_at,
                   host_key_offered = incus_server_cert_offered,
                   identity_public_key = incus_client_cert
             WHERE runtime = 'incus'
            """);
        schema.alterTable("servers", table -> {
            table.dropColumn("incus_client_key");
            table.dropColumn("incus_client_cert");
            table.dropColumn("incus_server_cert_offered");
            table.dropColumn("incus_server_cert_pinned_at");
            table.dropColumn("incus_server_cert_verified");
            table.dropColumn("incus_server_cert_fingerprint");
            table.dropColumn("incus_server_cert");
        });
    }
}
