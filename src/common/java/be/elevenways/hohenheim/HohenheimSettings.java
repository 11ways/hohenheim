package be.elevenways.hohenheim;

import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.setting.SettingGroup;
import be.elevenways.zenit.common.setting.SettingsContext;
import be.elevenways.zenit.common.validation.PathKind;

import java.util.List;

/**
 * All Hohenheim configuration settings, organized by group.
 */
@ZenitAutoLoad(loadInnerClasses = true)
public class HohenheimSettings {

    // AIDEV-NOTE: The context roots at hohenheim's OWN group (the standard
    // consumer shape; only ServerSettings roots at Zenit.SETTINGS). File keys
    // are RELATIVE to this root, so settings/hohenheim.dry keeps the flat
    // proxy/ssl/... shape. The server loads it via HohenheimSettingsFiles.
    public static final SettingGroup HOHENHEIM = Zenit.SETTINGS.createGroup("hohenheim")
        .label("Hohenheim");

    public static final SettingsContext VALUES = new SettingsContext(HOHENHEIM);

    // Nested groups below are force-loaded at compile time via @ZenitAutoLoad
    // (loadInnerClasses=true): Protoblast's Gradle plugin emits a reference to
    // each into the generated BlastAutoLoadInit, fired on first Blast use. No
    // per-group boilerplate here; adding a group is enough.

    // --- Proxy ---
    public abstract class Proxy {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("proxy")
            .label("Proxy")
            .describe("The reverse-proxy listeners and routing behaviour")
            .icon("route");

        public static final SettingDefinition<Integer> HTTP_PORT = GROUP.buildSetting("http_port", Integer.class)
            .defaultValue(80)
            .description("HTTP proxy listen port")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> HTTP_SOCKET_PATH = GROUP.buildSetting("http_socket_path", String.class)
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.ANY)
            .description("Optional Unix socket path for the HTTP proxy instead of a public TCP listener. "
                + "The socket bridges to a loopback TCP port, so on a multi-user host any local account "
                + "can still reach the proxy via 127.0.0.1 regardless of the socket permissions")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> HTTP_SOCKET_PERMISSIONS = GROUP.buildSetting("http_socket_permissions", String.class)
            .defaultValue("0660")
            .description("Octal permissions applied to the HTTP proxy Unix socket (limits socket access only, "
                + "not the internal loopback port)")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> HTTPS_PORT = GROUP.buildSetting("https_port", Integer.class)
            .defaultValue(443)
            .description("HTTPS proxy listen port")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> FALLBACK_ADDRESS = GROUP.buildSetting("fallback_address", String.class)
            .description("Address for requests that match no site")
            .build();

        public static final SettingDefinition<Boolean> FORCE_HTTPS = GROUP.buildSetting("force_https", Boolean.class)
            .defaultValue(true)
            .description("Redirect HTTP to HTTPS globally")
            .build();

        public static final SettingDefinition<String> IPV6_ADDRESS = GROUP.buildSetting("ipv6_address", String.class)
            .description("Optional IPv6 address for dedicated proxy listeners")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> NOT_FOUND_MESSAGE = GROUP.buildSetting("not_found_message", String.class)
            .defaultValue("There is no site configured for this domain.")
            .description("Error message for unmatched domains")
            .multiline()
            .build();

        public static final SettingDefinition<String> UNREACHABLE_MESSAGE = GROUP.buildSetting("unreachable_message", String.class)
            .defaultValue("The upstream server is not responding.")
            .description("Error message for unreachable upstreams")
            .multiline()
            .build();

        public static final SettingDefinition<Integer> FIRST_PORT = GROUP.buildSetting("first_port", Integer.class)
            .defaultValue(4748)
            .description("First TCP port assigned to managed child processes")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> TRUSTED_PROXY_KEYS = GROUP.buildSetting("trusted_proxy_keys", String.class)
            .description("Comma-delimited X-Hohenheim-Key values from trusted upstream proxies")
            .secret()
            .build();
    }

    // --- SSL/TLS ---
    public abstract class Ssl {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("ssl")
            .label("SSL / TLS")
            .describe("Automatic Let's Encrypt certificate issuance")
            .icon("lock");

        public static final SettingDefinition<Boolean> LETSENCRYPT_ENABLED = GROUP.buildSetting("letsencrypt_enabled", Boolean.class)
            .defaultValue(true)
            .description("Enable automatic Let's Encrypt certificates")
            .build();

        public static final SettingDefinition<String> LETSENCRYPT_EMAIL = GROUP.buildSetting("letsencrypt_email", String.class)
            .description("Email address for Let's Encrypt account")
            .build();

        public static final SettingDefinition<Boolean> LETSENCRYPT_STAGING = GROUP.buildSetting("letsencrypt_staging", Boolean.class)
            .defaultValue(false)
            .description("Use Let's Encrypt staging server")
            .build();

        public static final SettingDefinition<String> DNS_HOOK_COMMAND = GROUP.buildSetting("dns_hook_command", String.class)
            .description("Executable DNS-01 hook; called as: command present|cleanup record-name record-value")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> DNS_PROPAGATION_SECONDS = GROUP.buildSetting("dns_propagation_seconds", Integer.class)
            .defaultValue(30)
            .suffix("s")
            .description("Seconds to wait after publishing DNS-01 TXT records")
            .build();
    }

    // --- Authoritative DNS ---
    public abstract class Dns {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("dns")
            .label("DNS server")
            .describe("Authoritative DNS for the zones Hohenheim hosts")
            .icon("sitemap");

        public static final SettingDefinition<Boolean> ENABLED = GROUP.buildSetting("enabled", Boolean.class)
            .defaultValue(false)
            .description("Serve the configured DNS zones authoritatively on UDP and TCP. "
                + "The registrar must delegate each zone to this server before answers matter, "
                + "and production delegations need at least two name servers")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> BIND_ADDRESS = GROUP.buildSetting("bind_address", String.class)
            .defaultValue("0.0.0.0")
            .description("Address the DNS listeners bind to")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> PORT = GROUP.buildSetting("port", Integer.class)
            .defaultValue(53)
            .description("DNS listen port (UDP and TCP); port 53 usually needs elevated privileges "
                + "or a capability like CAP_NET_BIND_SERVICE")
            .restartRequired()
            .build();

        public static final SettingDefinition<Integer> RATE_LIMIT_PER_SECOND = GROUP
            .buildSetting("rate_limit_per_second", Integer.class)
            .defaultValue(20)
            .suffix("req/s")
            .description("Response-rate-limit per client network and query over UDP, the standard "
                + "mitigation against DNS reflection abuse. Every second limited response is answered "
                + "truncated so legitimate clients retry over TCP (never limited). 0 disables")
            .build();
    }

    // --- Logging ---
    // Note: the admin UI listens on Zenit's ServerSettings.Network.PORT.
    // The previous HohenheimSettings.Admin.PORT was displayed in the UI but
    // wired to nothing, so it was removed to avoid misleading operators.
    public abstract class Logging {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("logging")
            .label("Logging")
            .describe("Access logging for proxied requests")
            .icon("align-left");

        public static final SettingDefinition<Boolean> ACCESS_TO_FILE = GROUP.buildSetting("access_to_file", Boolean.class)
            .defaultValue(true)
            .description("Log access requests to file")
            .build();

        public static final SettingDefinition<String> ACCESS_PATH = GROUP.buildSetting("access_path", String.class)
            .defaultValue("/var/log/hohenheim/access.log")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.FILE)
            .description("Access log file path")
            .build();
    }

    // --- Node.js discovery ---
    public abstract class Node {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("node")
            .label("Node.js")
            .describe("Node.js runtime discovery")
            .icon("code");

        public static final SettingDefinition<String> LOCATIONS = GROUP.buildSetting("locations", String.class)
            .description("Additional N-style version directories, separated by commas or new lines")
            .multiline()
            .build();
    }

    // --- Storage ---
    public abstract class Storage {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("storage")
            .label("Storage")
            .describe("Filesystem locations for persistent data")
            .icon("folder");

        public static final SettingDefinition<String> DATA_PATH = GROUP.buildSetting("data_path", String.class)
            .defaultValue("data")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .description("Base directory for persistent data (git repos, etc.)")
            .restartRequired()
            .build();
    }

    // --- Database ---
    public abstract class Database {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("database")
            .label("Database")
            .describe("Hohenheim's own database and managed-database backups")
            .icon("database");

        public static final SettingDefinition<String> PATH = GROUP.buildSetting("path", String.class)
            .defaultValue("hohenheim.db")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.FILE)
            .description("SQLite database file path (used when 'url' is blank)")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> URL = GROUP.buildSetting("url", String.class)
            .description("Full JDBC URL to use instead of the SQLite file, e.g. "
                + "jdbc:postgresql://host/hohenheim or jdbc:mysql://host/hohenheim. "
                + "The engine is inferred from the URL scheme")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> ENGINE = GROUP.buildSetting("engine", String.class)
            .defaultValue("auto")
            .description("Database engine: auto (infer from url) or one of sqlite, duckdb, "
                + "postgres, mysql, mariadb, firebird, cockroach. Set explicitly for CockroachDB, "
                + "which shares the postgres URL scheme")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> USERNAME = GROUP.buildSetting("username", String.class)
            .description("Database username (server engines only; ignored for sqlite/duckdb)")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> PASSWORD = GROUP.buildSetting("password", String.class)
            .secret()
            .description("Database password (server engines only)")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> BACKUP_PATH = GROUP.buildSetting("backup_path", String.class)
            .defaultValue("data/backups")
            .filesystemPath(HohenheimPaths.SERVER_FILES, PathKind.DIRECTORY)
            .description("Directory for scheduled managed-database dumps")
            .build();

        public static final SettingDefinition<Integer> BACKUP_RETENTION = GROUP.buildSetting("backup_retention", Integer.class)
            .defaultValue(7)
            .description("Number of dumps to keep per managed database")
            .build();
    }

    // --- Security ---
    public abstract class Security {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("security")
            .label("Security")
            .describe("Hostname-scan detection, native IP bans and spamservice reputation")
            .icon("shield");

        public static final SettingDefinition<Integer> DOMAIN_MISS_THRESHOLD = GROUP.buildSetting("domain_miss_threshold", Integer.class)
            .defaultValue(5)
            .description("In-window domain misses before an IP's misses are recorded as security events")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_WINDOW_SECONDS = GROUP.buildSetting("domain_miss_window_seconds", Integer.class)
            .defaultValue(300)
            .suffix("s")
            .description("Sliding window for counting weighted security events towards a ban")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_BAN_THRESHOLD = GROUP.buildSetting("domain_miss_ban_threshold", Integer.class)
            .defaultValue(25)
            .description("In-window weighted event score before an IP is auto-banned")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_DECAY_PER_HIT = GROUP.buildSetting("domain_miss_decay_per_hit", Integer.class)
            .defaultValue(2)
            .description("Score forgiven for each successful route hit")
            .build();

        public static final SettingDefinition<Boolean> BANS_ENABLED = GROUP.buildSetting("bans_enabled", Boolean.class)
            .defaultValue(true)
            .description("Enforce IP bans in the proxy (HTTP refusal and TLS handshake refusal) "
                + "and create automatic bans when the event score crosses the threshold")
            .build();

        public static final SettingDefinition<List<String>> NEVER_BAN = GROUP
            .buildStringListSetting("never_ban")
            .defaultValue(List.of())
            .description("IPs, CIDR ranges and hostnames that may NEVER be banned, automatically "
                + "or manually; add your own operator addresses as separate entries. Hostnames are resolved "
                + "in the background (never on a request), keep their last resolved addresses "
                + "on failure, and resolved IPv6 addresses protect their whole /64")
            .build();

        public static final SettingDefinition<Boolean> NFTABLES_ENABLED = GROUP.buildSetting("nftables_enabled", Boolean.class)
            .defaultValue(false)
            .description("Also enforce bans in the kernel via nftables (requires passwordless "
                + "sudo for nft; the table 'inet hohenheim' is owned by Hohenheim)")
            .build();

        public static final SettingDefinition<String> NFTABLES_PORTS = GROUP.buildSetting("nftables_ports", String.class)
            .defaultValue("80,443")
            .description("Comma-separated TCP ports the nftables ban rule is scoped to; "
                + "never widen this to ports like 22 or 53 that other services depend on")
            .build();

        public static final SettingDefinition<Integer> AUTO_BAN_TTL_HOURS = GROUP.buildSetting("auto_ban_ttl_hours", Integer.class)
            .defaultValue(24)
            .suffix("h")
            .description("Lifetime of an automatically created ban")
            .build();

        public static final SettingDefinition<Integer> AUTO_BAN_BUDGET_PER_HOUR = GROUP.buildSetting("auto_ban_budget_per_hour", Integer.class)
            .defaultValue(50)
            .description("Maximum automatic bans (any trigger: threat scorer, reputation) in a "
                + "sliding hour; when exhausted further auto-bans are suppressed and logged "
                + "loudly, so a runaway trigger becomes a bounded incident instead of a mass "
                + "ban of all visitors (minimum 1; manual bans are never limited)")
            .build();

        public static final SettingDefinition<Integer> DEFAULT_EVENT_WEIGHT = GROUP.buildSetting("default_event_weight", Integer.class)
            .defaultValue(1)
            .description("Ban-score weight for event types without a built-in weight")
            .build();

        public static final SettingDefinition<String> REPUTATION_BAN_CATEGORIES = GROUP.buildSetting("reputation_ban_categories", String.class)
            .defaultValue("spam,auth")
            .description("Comma-separated, case-insensitive behavioral event categories whose spamservice counts "
                + "feed the reputation ban decision; dataset flags (vpn/hosting/tor/proxy) "
                + "NEVER count")
            .build();

        public static final SettingDefinition<Integer> REPUTATION_BAN_THRESHOLD = GROUP.buildSetting("reputation_ban_threshold", Integer.class)
            .defaultValue(25)
            .description("Ban an IP when its weighted net spamservice event count across the "
                + "configured categories reaches this value (minimum 1)")
            .build();

        public static final SettingDefinition<Integer> REPUTATION_POSITIVE_EVENT_WEIGHT = GROUP.buildSetting("reputation_positive_event_weight", Integer.class)
            .defaultValue(10)
            .description("Number of negative events offset by one positive event in the SAME "
                + "category (minimum 0; 0 disables positive credit)")
            .build();

        public static final SettingDefinition<Integer> REPUTATION_TTL_SECONDS = GROUP.buildSetting("reputation_ttl_seconds", Integer.class)
            .defaultValue(300)
            .suffix("s")
            .description("How long a spamservice reputation answer is cached in-process "
                + "(minimum 1 second; changing this requires a restart)")
            .restartRequired()
            .build();

    }

    // --- Proteus SSO (optional; password login is always available) ---
    public abstract class AuthProteus {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("auth_proteus")
            .label("Proteus SSO")
            .describe("Offer Proteus single sign-on next to password login")
            .icon("user");

        public static final SettingDefinition<Boolean> ENABLED = GROUP.buildSetting("enabled", Boolean.class)
            .defaultValue(false)
            .description("Offer Proteus SSO as a login option")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> ENDPOINT = GROUP.buildSetting("endpoint", String.class)
            .description("Proteus realm server URL")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> REALM_CLIENT = GROUP.buildSetting("realm_client", String.class)
            .description("Proteus realm client slug")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> ACCESS_KEY = GROUP.buildSetting("access_key", String.class)
            .description("Proteus realm access key")
            .secret()
            .restartRequired()
            .build();

        public static final SettingDefinition<String> AUTHENTICATOR = GROUP.buildSetting("authenticator", String.class)
            .defaultValue("password")
            .description("Proteus authenticator slug")
            .restartRequired()
            .build();
    }

    // --- Per-site proxy auth (gating proxied upstreams behind a provider) ---
    public abstract class ProxyAuth {
        public static final SettingGroup GROUP = HOHENHEIM.createGroup("proxy_auth")
            .label("Proxy auth")
            .describe("Sessions for auth-provider-gated proxied sites")
            .icon("id-badge");

        public static final SettingDefinition<Long> SESSION_TTL_SECONDS = GROUP.buildSetting("session_ttl_seconds", Long.class)
            .defaultValue(86400L)
            .suffix("s")
            .description("Lifetime of a proxy-auth session (seconds)")
            .build();

        public static final SettingDefinition<Long> PERSISTENT_TTL_SECONDS = GROUP.buildSetting("persistent_ttl_seconds", Long.class)
            .defaultValue(1209600L)
            .suffix("s")
            .description("Lifetime of a proxy-auth persistent (remember-me) cookie (seconds)")
            .build();
    }
}
