package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.ForeignKeyAction;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;

/**
 * Creates the full Hohenheim control-plane schema.
 *
 * AIDEV-NOTE: this file IS the schema. Hohenheim has no deployed installations, so the
 * M003..M092 chain that grew this schema incrementally was folded into this one migration
 * on 2026-08-13; every backfill and heal in it became nothing (there was no data to
 * migrate) and M041's default spamservice row moved to SpamserviceInstallationSeeder.
 * A schema change during development EDITS THIS FILE IN PLACE -- never append an
 * incremental or backfill migration. Editing an already-applied migration trips zenit's
 * database.migration_integrity check, which REFUSES the boot by default on any dev
 * database that predates the edit; that refusal is the signal to drop and recreate the
 * dev database, never to downgrade the setting to warn or off.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public class InitialMigration extends HohenheimMigration {

    public InitialMigration() {
        super("001", "Create schema");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("sites", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("slug", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("upstream_kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50));
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(true));
            table.addColumn("settings", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("description", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("idle"));
            table.timestamps();
            table.addColumn("deleted_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("access_list_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            // The instance an upstream_kind=instance site serves. No declared FK: the
            // instances table is created further down, and this file follows the
            // instances.environment_id precedent -- a forward reference is a plain
            // indexed column, the invariant lives in SiteModel's before-validate hook.
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("auth_provider_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("security_report_token", ColumnType.STRING,
                column -> column.nullable(true).maxLength(96));
            table.addColumn("quota_bucket", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.unique("sites_slug_unique", List.of("slug"));
            table.addIndex("sites_upstream_kind_index", List.of("upstream_kind"));
            table.addIndex("sites_instance_id_index", List.of("instance_id"));
        });

        schema.createTable("site_domains", table -> {
            table.id();
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("sites", "id"));
            table.addColumn("hostname", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("match_type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20).defaultValue("exact"));
            table.addColumn("listen_on", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("path", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("strip_path", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("force_ssl", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(true));
            table.addColumn("certificate_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("hsts_enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("hsts_subdomains", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("custom_headers", ColumnType.JSON,
                column -> column.nullable(true));
            table.timestamps();
            table.addColumn("exclude_from_letsencrypt", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("response_headers", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("live_route_key", ColumnType.STRING,
                column -> column.nullable(true).maxLength(1024));
            table.addColumn("generated_by", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("generated_for_model", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("generated_for_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("generated_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addIndex("site_domains_site_id_index", List.of("site_id"));
            table.addIndex("site_domains_hostname_index", List.of("hostname"));
            table.unique("site_domains_live_route_key_unique", List.of("live_route_key"));
            table.addIndex("idx_site_domains_generated_for_id", List.of("generated_for_id"));
        });

        schema.createTable("certificates", table -> {
            table.id();
            table.addColumn("nice_name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("provider", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("letsencrypt"));
            table.addColumn("certificate_pem", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("private_key_pem", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("expires_on", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("auto_renew", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(true));
            table.timestamps();
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20).defaultValue("active"));
            table.addColumn("issued_on", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("renewal_error", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("domain_names_text", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("error_count", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(0));
            table.addColumn("next_attempt_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("letsencrypt_email", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("expiry_notified_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("challenge_type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20));
            table.addColumn("dns_publisher", ColumnType.STRING,
                column -> column.nullable(true).maxLength(80));
            table.addColumn("requested_by_user_id", ColumnType.INTEGER,
                column -> column.nullable(true));
        });

        schema.createTable("access_lists", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("satisfy", ColumnType.STRING,
                column -> column.nullable(true).maxLength(10).defaultValue("any"));
            table.timestamps();
        });

        // The rule TREE behind an access list. parent_id is the nesting edge (null = a
        // direct child of the list's implicit root group); data holds the per-type
        // sub-schema the row's type declares.
        schema.createTable("access_rules", table -> {
            table.id();
            table.addColumn("access_list_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("access_lists", "id"));
            table.addColumn("parent_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("access_rules", "id"));
            table.addColumn("sort", ColumnType.INTEGER,
                column -> column.nullable(false).defaultValue(0));
            table.addColumn("type", ColumnType.STRING,
                column -> column.nullable(false).maxLength(32));
            table.addColumn("data", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("search_text", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(false).defaultValue(true));
            table.timestamps();
            table.addIndex("access_rules_access_list_id_index", List.of("access_list_id"));
            table.addIndex("access_rules_parent_id_index", List.of("parent_id"));
        });

        schema.createTable("proclogs", table -> {
            table.id();
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("sites", "id"));
            table.addColumn("pid", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("log_html", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("line_count", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(0));
            table.timestamps();
            table.addColumn("saved_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addIndex("proclogs_site_id_index", List.of("site_id"));
            table.addIndex("proclogs_site_id_created_at_index", List.of("site_id", "created_at"));
        });

        schema.createTable("system_users", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("uid", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("gid", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("home", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("gecos", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("obsolete", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("last_seen_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.timestamps();
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("managed", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.unique("system_users_name_unique", List.of("name"));
            table.addIndex("system_users_obsolete_index", List.of("obsolete"));
            table.unique("system_users_site_id_unique", List.of("site_id"));
        });

        schema.createTable("node_versions", table -> {
            table.id();
            table.addColumn("version", ColumnType.STRING,
                column -> column.nullable(true).maxLength(64));
            table.addColumn("path", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("source", ColumnType.STRING,
                column -> column.nullable(true).maxLength(64));
            table.addColumn("obsolete", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("last_seen_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.timestamps();
            table.unique("node_versions_path_unique", List.of("path"));
            table.addIndex("node_versions_obsolete_index", List.of("obsolete"));
        });

        schema.createTable("servers", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128));
            table.addColumn("mode", ColumnType.STRING,
                column -> column.nullable(true).maxLength(16));
            table.addColumn("ssh_target", ColumnType.STRING,
                column -> column.nullable(true).maxLength(256));
            table.timestamps();
            table.addColumn("posture", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("trusted_only"));
            table.addColumn("admission", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("blocked"));
            table.addColumn("capabilities", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("probed_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("preflight_ok", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("last_seen_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("last_error_kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32));
            table.addColumn("last_error", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("host_key_fingerprint", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("controller_version", ColumnType.STRING,
                column -> column.nullable(true).maxLength(64));
            table.addColumn("host_key", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("host_key_verified", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("host_key_pinned_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("host_key_offered", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("identity_public_key", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("identity_private_key", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("public_ipv4", ColumnType.STRING,
                column -> column.nullable(true).maxLength(45));
            table.addColumn("public_ipv6", ColumnType.STRING,
                column -> column.nullable(true).maxLength(45));
            table.addColumn("runtime", ColumnType.STRING,
                column -> column.nullable(false).maxLength(20).defaultValue("docker"));
            table.addColumn("incus_url", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("incus_server_cert", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("incus_server_cert_fingerprint", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("incus_server_cert_verified", ColumnType.BOOLEAN,
                column -> column.nullable(false).defaultValue(false));
            table.addColumn("incus_server_cert_pinned_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("incus_server_cert_offered", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("incus_client_cert", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("incus_client_key", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("quarantined_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("quarantine_reason", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("acknowledged_posture", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32));
            table.addColumn("acknowledged_warning_version", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("acknowledged_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("acknowledged_by", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("acknowledged_by_label", ColumnType.STRING,
                column -> column.nullable(true).maxLength(200));
            // What the filesystem under this host's volume root can do, DETECTED by the
            // preflight probe (VolumeBackends) and never operator-declared.
            table.addColumn("volume_backend", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("none"));
            table.addColumn("volume_root", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("volume_backend_detail", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("volume_probed_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.unique("servers_name_unique", List.of("name"));
        });

        schema.createTable("managed_databases", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128));
            table.addColumn("engine", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32));
            table.addColumn("image", ColumnType.STRING,
                column -> column.nullable(true).maxLength(256));
            table.addColumn("db_user", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128));
            table.addColumn("db_password", ColumnType.STRING,
                column -> column.nullable(true).maxLength(256));
            table.addColumn("db_name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128));
            table.addColumn("ephemeral", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.timestamps();
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20).defaultValue("active"));
            table.addColumn("memory_limit_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("cpu_limit", ColumnType.DOUBLE,
                column -> column.nullable(true));
            table.addColumn("server_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("servers", "id"));
            table.addColumn("quota_bucket", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.unique("managed_databases_name_unique", List.of("name"));
        });

        schema.createTable("notification_channels", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128));
            table.addColumn("kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(16));
            table.addColumn("format", ColumnType.STRING,
                column -> column.nullable(true).maxLength(16));
            table.addColumn("url", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.timestamps();
            table.addColumn("events", ColumnType.JSON,
                column -> column.nullable(true));
            table.unique("notification_channels_name_unique", List.of("name"));
        });

        schema.createTable("site_auth_providers", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("provider_type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50));
            table.addColumn("config", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("required_permission", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.timestamps();
        });

        schema.createTable("site_sessions", table -> {
            table.addColumn("id", ColumnType.STRING,
                column -> column.nullable(false).maxLength(64).unique().primaryKey());
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("data", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("created_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("expires_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addIndex("site_sessions_site_id_index", List.of("site_id"));
            table.addIndex("site_sessions_expires_at_index", List.of("expires_at"));
        });

        schema.createTable("deployments", table -> {
            table.id();
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20));
            table.addColumn("reason", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50));
            table.addColumn("commit_sha", ColumnType.STRING,
                column -> column.nullable(true).maxLength(64));
            table.addColumn("slot", ColumnType.STRING,
                column -> column.nullable(true).maxLength(10));
            table.addColumn("error", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("log", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("started_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("duration_ms", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("deployments_site_id_index", List.of("site_id"));
        });

        schema.createTable("site_databases", table -> {
            table.id();
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("database_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("env_prefix", ColumnType.STRING,
                column -> column.nullable(true).maxLength(40));
            table.timestamps();
            table.addIndex("site_databases_site_id_index", List.of("site_id"));
            table.addIndex("site_databases_database_id_index", List.of("database_id"));
        });

        schema.createTable("dns_zones", table -> {
            table.id();
            table.addColumn("origin", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("soa_primary_ns", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("soa_contact", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("serial", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(1));
            table.addColumn("default_ttl", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(3600));
            table.addColumn("negative_ttl", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(300));
            table.addColumn("soa_refresh", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(7200));
            table.addColumn("soa_retry", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(3600));
            table.addColumn("soa_expire", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(1209600));
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(true));
            table.timestamps();
            table.addColumn("role", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20).defaultValue("primary"));
            table.addColumn("primary_peer_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("transfer_status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20));
            table.addColumn("transfer_message", ColumnType.STRING,
                column -> column.nullable(true).maxLength(500));
            table.addColumn("last_checked_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("last_transfer_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("replica_records", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("dnssec_enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("dnssec_algorithm", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(13));
            table.addColumn("dnssec_private_key", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("dnssec_public_key", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("dnssec_key_tag", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.unique("dns_zones_origin_unique", List.of("origin"));
        });

        schema.createTable("dns_records", table -> {
            table.id();
            table.addColumn("zone_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("dns_zones", "id"));
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(10));
            table.addColumn("ttl", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("value", ColumnType.STRING,
                column -> column.nullable(true).maxLength(4096));
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(true));
            table.addColumn("managed_by", ColumnType.STRING,
                column -> column.nullable(true).maxLength(40));
            table.timestamps();
            table.addColumn("generated_by", ColumnType.STRING,
                column -> column.nullable(true).maxLength(40));
            table.addColumn("generated_for_model", ColumnType.STRING,
                column -> column.nullable(true).maxLength(120));
            table.addColumn("generated_for_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("generated_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("data", ColumnType.JSON,
                column -> column.nullable(true));
            table.addIndex("dns_records_zone_id_index", List.of("zone_id"));
            table.addIndex("dns_records_name_index", List.of("name"));
        });

        schema.createTable("dns_peers", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(120));
            table.addColumn("peer_type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20).defaultValue("nameserver"));
            table.addColumn("base_url", ColumnType.STRING,
                column -> column.nullable(true).maxLength(500));
            table.addColumn("api_key", ColumnType.STRING,
                column -> column.nullable(true).maxLength(200));
            table.addColumn("transfer_host", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("transfer_port", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(53));
            table.addColumn("tsig_key_name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("tsig_algorithm", ColumnType.STRING,
                column -> column.nullable(true).maxLength(40).defaultValue("hmac-sha256"));
            table.addColumn("tsig_secret", ColumnType.STRING,
                column -> column.nullable(true).maxLength(500));
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(true));
            table.timestamps();
            table.unique("dns_peers_name_unique", List.of("name"));
        });

        schema.createTable("dns_zone_peers", table -> {
            table.id();
            table.addColumn("zone_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("dns_zones", "id"));
            table.addColumn("peer_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("dns_peers", "id"));
            table.timestamps();
            table.addIndex("dns_zone_peers_zone_id_index", List.of("zone_id"));
        });

        schema.createTable("bans", table -> {
            table.id();
            table.addColumn("ip", ColumnType.STRING,
                column -> column.nullable(true).maxLength(64));
            table.addColumn("reason", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("source", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("event_type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("expires_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("active", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(true));
            table.addColumn("lifted_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("lifted_by", ColumnType.STRING,
                column -> column.nullable(true).maxLength(200));
            table.timestamps();
            table.addIndex("bans_ip_index", List.of("ip"));
            table.addIndex("bans_active_index", List.of("active"));
        });

        schema.createTable("spamservice_installations", table -> {
            table.id();
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("port", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(8095));
            table.addColumn("system_user_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("system_users", "id").onDelete(ForeignKeyAction.RESTRICT));
            table.addColumn("max_heap_mb", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(512));
            table.addColumn("controller_key", ColumnType.STRING,
                column -> column.nullable(true).maxLength(256));
            table.timestamps();
        });

        schema.createTable("stacks", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100).unique());
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true));
            table.addColumn("registry_server", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("registry_user", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("registry_password", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("description", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20));
            table.timestamps();
            table.addColumn("server_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("servers", "id"));
        });

        schema.createTable("stack_services", table -> {
            table.id();
            table.addColumn("stack_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true));
            table.addColumn("image", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("command", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("environment", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("health_cmd", ColumnType.STRING,
                column -> column.nullable(true).maxLength(500));
            table.addColumn("health_interval_seconds", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("health_timeout_seconds", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("health_retries", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("health_start_period_seconds", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("restart_policy", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20));
            table.addColumn("memory_limit_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("cpu_limit", ColumnType.DOUBLE,
                column -> column.nullable(true));
            table.timestamps();
            table.addColumn("capabilities", ColumnType.JSON,
                column -> column.nullable(true));
            table.addIndex("stack_services_stack_id_index", List.of("stack_id"));
            table.unique("stack_services_stack_id_name_unique", List.of("stack_id", "name"));
        });

        schema.createTable("stack_services_mounts", table -> {
            table.id();
            table.addColumn("stack_service_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("stack_services", "id").onDelete(ForeignKeyAction.CASCADE));
            table.addColumn("order_key", ColumnType.LONG,
                column -> column.nullable(false));
            table.addColumn("type", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("container_path", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("external_name", ColumnType.STRING,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("stack_services_mounts_stack_service_id_order_key_index", List.of("stack_service_id", "order_key"));
        });

        schema.createTable("stack_services_ports", table -> {
            table.id();
            table.addColumn("stack_service_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("stack_services", "id").onDelete(ForeignKeyAction.CASCADE));
            table.addColumn("order_key", ColumnType.LONG,
                column -> column.nullable(false));
            table.addColumn("container_port", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("host_port", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("protocol", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("host_ip", ColumnType.STRING,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("stack_services_ports_stack_service_id_order_key_index", List.of("stack_service_id", "order_key"));
        });

        schema.createTable("stack_services_depends_on", table -> {
            table.id();
            table.addColumn("stack_service_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("stack_services", "id").onDelete(ForeignKeyAction.CASCADE));
            table.addColumn("order_key", ColumnType.LONG,
                column -> column.nullable(false));
            table.addColumn("service", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("condition", ColumnType.STRING,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("stack_services_depends_on_stack_service_id_order_key_index", List.of("stack_service_id", "order_key"));
        });

        schema.createTable("stack_files", table -> {
            table.id();
            table.addColumn("stack_service_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("container_path", ColumnType.STRING,
                column -> column.nullable(true).maxLength(500));
            table.addColumn("content", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("mode", ColumnType.STRING,
                column -> column.nullable(true).maxLength(10));
            table.timestamps();
            table.addIndex("stack_files_stack_service_id_index", List.of("stack_service_id"));
            table.unique("stack_files_stack_service_id_container_path_unique", List.of("stack_service_id", "container_path"));
        });

        schema.createTable("stack_deployments", table -> {
            table.id();
            table.addColumn("stack_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20));
            table.addColumn("reason", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50));
            table.addColumn("error", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("log", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("spec", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("started_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("duration_ms", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("stack_deployments_stack_id_index", List.of("stack_id"));
        });

        schema.createTable("reconcile_findings", table -> {
            table.id();
            table.addColumn("server_name", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("kind", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("resource_name", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("bucket", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("evidence", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("owner_model", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("owner_id", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("detail", ColumnType.STRING,
                column -> column.nullable(true));
            table.timestamps();
        });

        schema.createTable("port_allocations", table -> {
            table.id();
            table.addColumn("server_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("servers", "id"));
            table.addColumn("host_ip", ColumnType.STRING,
                column -> column.nullable(true).maxLength(64));
            table.addColumn("port", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("protocol", ColumnType.STRING,
                column -> column.nullable(true).maxLength(8));
            table.addColumn("claim_key", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128));
            table.addColumn("owner_model", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128));
            table.addColumn("owner_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("note", ColumnType.STRING,
                column -> column.nullable(true).maxLength(256));
            table.timestamps();
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(16));
            table.addColumn("controller_fence", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("allocation_mode", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20));
            table.unique("port_allocations_claim_key_unique", List.of("claim_key"));
        });

        schema.createTable("released_route_claims", table -> {
            table.id();
            table.addColumn("claim_key", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128));
            table.addColumn("hostname", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("former_site_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("former_subjects", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("released_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("match_type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(16));
            table.addIndex("released_route_claims_claim_key_index", List.of("claim_key"));
        });

        schema.createTable("instance_quotas", table -> {
            table.id();
            table.addColumn("subjects", ColumnType.STRING,
                column -> column.nullable(false).maxLength(400));
            table.addColumn("max_instances", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.timestamps();
            table.addColumn("max_disk_gb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("max_nics", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("max_memory_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("max_sites", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("max_databases", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.unique("instance_quotas_subjects_unique", List.of("subjects"));
        });

        schema.createTable("backup_targets", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("settings", ColumnType.JSON,
                column -> column.nullable(true));
            table.timestamps();
            // AIDEV-NOTE: the name is the operator-facing identity of a backup target and
            // the string every backup row and schedule refers to it by; the old chain never
            // constrained it, so two targets could share one name and no reference could say
            // which one it meant. Folded in with the consolidation (review finding, M058).
            table.unique("backup_targets_name_unique", List.of("name"));
        });

        schema.createTable("instance_templates", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("description", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("settings", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("version", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(1));
            table.addColumn("install_image", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("install_script", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("reinstall_policy", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("preserve"));
            table.addColumn("readiness_line", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("stop_command", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("approved_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("approved_by_user_id", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("source", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("source_checksum", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("imported_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.timestamps();
            table.addColumn("update_script", ColumnType.TEXT,
                column -> column.nullable(true));
            // The yolk/egg split: a template may name the runtime image it layers hooks
            // over instead of re-declaring the image (phase-0 design section 4.6).
            table.addColumn("runtime_image_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("start_command", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("readiness_kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("port"));
            table.addColumn("readiness_target", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("stop_kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("signal"));
            table.addColumn("stop_grace_seconds", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(10));
            table.addColumn("console_kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("plain"));
        });

        schema.createTable("runtime_images", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(false).maxLength(128));
            table.addColumn("description", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("icon", ColumnType.STRING,
                column -> column.nullable(true).maxLength(64));
            table.addColumn("docker_image", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("incus_image", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("build_context", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("default_command", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("default_port", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("default_build_command", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("workdir", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("shell", ColumnType.STRING,
                column -> column.nullable(true).maxLength(128).defaultValue("/bin/bash"));
            table.addColumn("uid_mode", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("mapped"));
            table.addColumn("builtin", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(true));
            table.timestamps();
            table.unique("runtime_images_name_unique", List.of("name"));
        });

        schema.createTable("instance_template_volumes", table -> {
            table.id();
            table.addColumn("template_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("instance_templates", "id"));
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(false).maxLength(128));
            table.addColumn("container_path", ColumnType.STRING,
                column -> column.nullable(false).maxLength(512));
            table.addColumn("quota_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("exclusive", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.timestamps();
            table.unique("instance_template_volumes_unique", List.of("template_id", "name"));
        });

        schema.createTable("instances", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("settings", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("server_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("servers", "id"));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("created"));
            table.timestamps();
            table.addColumn("deleted_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("quota_bucket", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("claim_fence", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("backup_target_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("backup_targets", "id"));
            table.addColumn("template_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("instance_templates", "id"));
            table.addColumn("install_state", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("none"));
            table.addColumn("install_error", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("crash_policy", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("none"));
            table.addColumn("generated_by", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("generated_for_model", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("generated_for_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("generated_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("runtime_role", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20).defaultValue("serving"));
            table.addColumn("environment_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("image_fingerprint", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("migrate_target_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("capacity_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("root_disk_bucket", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("disk_used_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("disk_limit_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("disk_observed_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("quota_memory_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("migrate_reserved_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("runtime_image_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("runtime_images", "id"));
            table.addIndex("instances_server_id_index", List.of("server_id"));
            table.addIndex("idx_instances_generated_for_id", List.of("generated_for_id"));
        });

        schema.createTable("instance_volumes", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("instances", "id"));
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(false).maxLength(128));
            table.addColumn("container_path", ColumnType.STRING,
                column -> column.nullable(false).maxLength(512));
            table.addColumn("quota_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("exclusive", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            // Derived from <data_path>/volumes/<instance>/<name> and stored as EVIDENCE of
            // the directory a reclaim would remove; the deploy path re-derives.
            table.addColumn("host_path", ColumnType.STRING,
                column -> column.nullable(true).maxLength(1024));
            table.addColumn("used_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("observed_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.timestamps();
            table.unique("instance_volumes_unique", List.of("instance_id", "name"));
        });

        schema.createTable("instance_snapshots", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("instances", "id"));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("failed"));
            table.addColumn("note", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("directory", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("volumes", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("total_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("error", ColumnType.TEXT,
                column -> column.nullable(true));
            table.timestamps();
            table.addColumn("native_name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addIndex("instance_snapshots_instance_id_index", List.of("instance_id"));
        });

        schema.createTable("instance_backups", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("instances", "id"));
            table.addColumn("target_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("backup_targets", "id"));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("failed"));
            table.addColumn("remote_key", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("sha256", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("size_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("summary", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("error", ColumnType.TEXT,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("instance_backups_instance_id_index", List.of("instance_id"));
        });

        schema.createTable("instance_template_variables", table -> {
            table.id();
            table.addColumn("template_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("instance_templates", "id"));
            table.addColumn("key", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("label", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("description", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("settings", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("required", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("default_value", ColumnType.STRING,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("instance_template_variables_template_id_index", List.of("template_id"));
        });

        schema.createTable("instance_template_files", table -> {
            table.id();
            table.addColumn("template_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("instance_templates", "id"));
            table.addColumn("container_path", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("content", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("mode", ColumnType.STRING,
                column -> column.nullable(true).maxLength(10).defaultValue("0644"));
            table.timestamps();
            table.addIndex("instance_template_files_template_id_index", List.of("template_id"));
        });

        schema.createTable("instance_variables", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("instances", "id"));
            table.addColumn("key", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("plain"));
            table.addColumn("plain_value", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("secret_value", ColumnType.TEXT,
                column -> column.nullable(true));
            table.timestamps();
            table.addColumn("environment_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addIndex("instance_variables_instance_id_index", List.of("instance_id"));
        });

        schema.createTable("instance_files", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(true).references("instances", "id"));
            table.addColumn("container_path", ColumnType.STRING,
                column -> column.nullable(true).maxLength(512));
            table.addColumn("content", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("mode", ColumnType.STRING,
                column -> column.nullable(true).maxLength(10).defaultValue("0644"));
            table.timestamps();
            table.addColumn("generated_by", ColumnType.STRING,
                column -> column.nullable(true).maxLength(40));
            table.addColumn("generated_for_model", ColumnType.STRING,
                column -> column.nullable(true).maxLength(120));
            table.addColumn("generated_for_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("generated_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addIndex("instance_files_instance_id_index", List.of("instance_id"));
        });

        schema.createTable("game_domains", table -> {
            table.id();
            table.addColumn("site_domain_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("backend_instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("proxy_instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("backend_port", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("enabled", ColumnType.BOOLEAN,
                column -> column.nullable(true));
            table.timestamps();
            table.unique("game_domains_domain_proxy", List.of("site_domain_id", "proxy_instance_id"));
            table.addIndex("game_domains_backend_instance_id_index", List.of("backend_instance_id"));
        });

        schema.createTable("build_operations", table -> {
            table.id();
            table.addColumn("builder_kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("dockerfile"));
            table.addColumn("for_model", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("for_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("running"));
            table.addColumn("source_ref", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("image_id", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("tag", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("exit_code", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("failure_reason", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("log", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("cpu_limit", ColumnType.DOUBLE,
                column -> column.nullable(true));
            table.addColumn("memory_limit_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("disk_limit_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("pids_limit", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("timeout_seconds", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("peak_disk_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("artifact_bytes", ColumnType.LONG,
                column -> column.nullable(true));
            table.addColumn("started_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("duration_ms", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.timestamps();
            table.addColumn("detection", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addIndex("build_operations_for_id_index", List.of("for_id"));
        });

        schema.createTable("release_operations", table -> {
            table.id();
            table.addColumn("kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("release"));
            table.addColumn("for_model", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("for_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50).defaultValue("pending"));
            table.addColumn("image_id", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("candidate_instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("retired_instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("site_fingerprint", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("spec_fingerprint", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("failure_reason", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("step_log", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("started_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("duration_ms", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("release_operations_for_id_index", List.of("for_id"));
        });

        schema.createTable("projects", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("description", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("group_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.timestamps();
        });

        schema.createTable("environments", table -> {
            table.id();
            table.addColumn("project_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("projects", "id"));
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("description", ColumnType.TEXT,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("environments_project_id_index", List.of("project_id"));
        });

        schema.createTable("git_providers", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("kind", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50));
            table.addColumn("base_url", ColumnType.STRING,
                column -> column.nullable(true).maxLength(500));
            // Per-kind NON-SECRET settings (the GitHub App identifiers); every credential
            // stays a column below, because a JSON sub-field cannot be encrypted.
            table.addColumn("settings", ColumnType.JSON,
                column -> column.nullable(true));
            table.addColumn("shared", ColumnType.BOOLEAN,
                column -> column.nullable(true).defaultValue(false));
            table.addColumn("access_token", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("app_private_key_pem", ColumnType.TEXT,
                column -> column.nullable(true));
            table.timestamps();
        });

        schema.createTable("webhook_deliveries", table -> {
            table.id();
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("sites", "id"));
            table.addColumn("delivery_key", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("event", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("action", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("received_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.timestamps();
            table.unique("webhook_deliveries_site_key_unique", List.of("site_id", "delivery_key"));
        });

        schema.createTable("preview_deployments", table -> {
            table.id();
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("sites", "id"));
            table.addColumn("ref", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("pr_number", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("head_sha", ColumnType.STRING,
                column -> column.nullable(true).maxLength(100));
            table.addColumn("hostname", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(true).maxLength(50));
            table.addColumn("expires_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("quota_bucket", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("last_error", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("deleted_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("preview_deployments_site_id_index", List.of("site_id"));
            table.addIndex("preview_deployments_expires_at_index", List.of("expires_at"));
        });

        schema.createTable("instance_devices", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("instances", "id"));
            table.addColumn("type", ColumnType.STRING,
                column -> column.nullable(true).maxLength(20));
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(true).maxLength(63));
            table.addColumn("size_gb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("source_media", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("quota_bucket", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.timestamps();
            table.unique("instance_devices_name_unique", List.of("instance_id", "name"));
            table.addIndex("instance_devices_instance_id_index", List.of("instance_id"));
        });

        schema.createTable("controller_identity", table -> {
            table.id();
            table.addColumn("singleton", ColumnType.INTEGER,
                column -> column.nullable(true).defaultValue(1));
            table.addColumn("token", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32));
            table.timestamps();
            table.unique("controller_identity_singleton_unique", List.of("singleton"));
            table.unique("controller_identity_token_unique", List.of("token"));
        });

        schema.createTable("instance_logs", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(false));
            table.addColumn("handle", ColumnType.STRING,
                column -> column.nullable(true).maxLength(191));
            table.addColumn("log_text", ColumnType.TEXT,
                column -> column.nullable(true));
            table.addColumn("line_count", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("saved_at", ColumnType.DATETIME,
                column -> column.nullable(true));
            table.timestamps();
            table.addIndex("instance_logs_instance_created", List.of("instance_id", "created_at"));
        });

        schema.createTable("instance_databases", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("database_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("env_prefix", ColumnType.STRING,
                column -> column.nullable(true).maxLength(40));
            table.timestamps();
            table.addIndex("instance_databases_instance_id_index", List.of("instance_id"));
            table.addIndex("instance_databases_database_id_index", List.of("database_id"));
        });

        schema.createTable("dns_dyndns_credentials", table -> {
            table.id();
            table.addColumn("record_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("token_digest", ColumnType.STRING,
                column -> column.nullable(true).maxLength(96));
            table.timestamps();
            // AIDEV-NOTE: one dyndns credential per DNS record is the invariant every
            // reader assumes (the resolver takes the first row it finds); the old chain
            // only indexed it. Folded in with the consolidation (review finding, M091).
            table.unique("dns_dyndns_credentials_record_id_unique", List.of("record_id"));
            table.addIndex("dns_dyndns_credentials_token_digest_index", List.of("token_digest"));
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("dns_dyndns_credentials");
        schema.dropTable("instance_databases");
        schema.dropTable("instance_logs");
        schema.dropTable("controller_identity");
        schema.dropTable("instance_devices");
        schema.dropTable("preview_deployments");
        schema.dropTable("webhook_deliveries");
        schema.dropTable("git_providers");
        schema.dropTable("environments");
        schema.dropTable("projects");
        schema.dropTable("release_operations");
        schema.dropTable("build_operations");
        schema.dropTable("game_domains");
        schema.dropTable("instance_files");
        schema.dropTable("instance_variables");
        schema.dropTable("instance_template_files");
        schema.dropTable("instance_template_variables");
        schema.dropTable("instance_backups");
        schema.dropTable("instance_snapshots");
        schema.dropTable("instance_volumes");
        schema.dropTable("instances");
        schema.dropTable("instance_template_volumes");
        schema.dropTable("instance_templates");
        schema.dropTable("runtime_images");
        schema.dropTable("backup_targets");
        schema.dropTable("instance_quotas");
        schema.dropTable("released_route_claims");
        schema.dropTable("port_allocations");
        schema.dropTable("reconcile_findings");
        schema.dropTable("stack_deployments");
        schema.dropTable("stack_files");
        schema.dropTable("stack_services_depends_on");
        schema.dropTable("stack_services_ports");
        schema.dropTable("stack_services_mounts");
        schema.dropTable("stack_services");
        schema.dropTable("stacks");
        schema.dropTable("spamservice_installations");
        schema.dropTable("bans");
        schema.dropTable("dns_zone_peers");
        schema.dropTable("dns_peers");
        schema.dropTable("dns_records");
        schema.dropTable("dns_zones");
        schema.dropTable("site_databases");
        schema.dropTable("deployments");
        schema.dropTable("site_sessions");
        schema.dropTable("site_auth_providers");
        schema.dropTable("notification_channels");
        schema.dropTable("managed_databases");
        schema.dropTable("servers");
        schema.dropTable("node_versions");
        schema.dropTable("system_users");
        schema.dropTable("proclogs");
        schema.dropTable("access_rules");
        schema.dropTable("access_lists");
        schema.dropTable("certificates");
        schema.dropTable("site_domains");
        schema.dropTable("sites");
    }
}
