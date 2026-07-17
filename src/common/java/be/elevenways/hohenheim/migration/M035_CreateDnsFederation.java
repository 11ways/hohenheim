package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * DNS federation: per-zone primary/secondary role, transfer peers, and the
 * replica bookkeeping columns.
 */
public class M035_CreateDnsFederation extends Migration {

    public M035_CreateDnsFederation() {
        super("2026_07_17_000035", "Add DNS zone roles, transfer peers, and replica state");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("dns_peers", table -> {
            table.id();
            table.string("name", 120);
            table.unique("name");
            // HTTPS base URL of the peer's admin server, for edit forwarding.
            table.addColumn("base_url", ColumnType.STRING, col -> col.maxLength(500).nullable(true));
            table.addColumn("api_key", ColumnType.STRING, col -> col.maxLength(200).nullable(true));
            // DNS zone-transfer channel.
            table.addColumn("transfer_host", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("transfer_port", ColumnType.INTEGER, col -> col.defaultValue(53));
            table.addColumn("tsig_key_name", ColumnType.STRING, col -> col.maxLength(255).nullable(true));
            table.addColumn("tsig_algorithm", ColumnType.STRING,
                col -> col.maxLength(40).defaultValue("hmac-sha256"));
            table.addColumn("tsig_secret", ColumnType.STRING, col -> col.maxLength(500).nullable(true));
            table.addColumn("enabled", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.timestamps();
        });

        schema.alterTable("dns_zones", table -> {
            // 'primary' (owned + edited here) or 'secondary' (replicated from a peer).
            table.addColumn("role", ColumnType.STRING, col -> col.maxLength(20).defaultValue("primary"));
            table.addColumn("primary_peer_id", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("transfer_status", ColumnType.STRING, col -> col.maxLength(20).nullable(true));
            table.addColumn("transfer_message", ColumnType.STRING, col -> col.maxLength(500).nullable(true));
            table.addColumn("last_checked_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addColumn("last_transfer_at", ColumnType.DATETIME, col -> col.nullable(true));
            // Master-file text of the last successful transfer, so a secondary
            // keeps serving across restarts even when its primary is down.
            table.addColumn("replica_records", ColumnType.TEXT, col -> col.nullable(true));
        });

        // The secondaries a primary zone notifies and authorizes AXFR for.
        schema.createTable("dns_zone_peers", table -> {
            table.id();
            table.foreignId("zone_id", "dns_zones");
            table.foreignId("peer_id", "dns_peers");
            table.timestamps();
            table.addIndex("zone_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("dns_zone_peers");
        schema.alterTable("dns_zones", table -> {
            table.dropColumn("role");
            table.dropColumn("primary_peer_id");
            table.dropColumn("transfer_status");
            table.dropColumn("transfer_message");
            table.dropColumn("last_checked_at");
            table.dropColumn("last_transfer_at");
            table.dropColumn("replica_records");
        });
        schema.dropTable("dns_peers");
    }
}
