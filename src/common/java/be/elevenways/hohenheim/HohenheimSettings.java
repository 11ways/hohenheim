package be.elevenways.hohenheim;

import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.annotation.ZenitAutoLoad;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.setting.SettingGroup;
import be.elevenways.zenit.common.setting.SettingsContext;

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
            .description("Access log file path")
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
            .description("SQLite database file path")
            .restartRequired()
            .build();

        public static final SettingDefinition<String> BACKUP_PATH = GROUP.buildSetting("backup_path", String.class)
            .defaultValue("data/backups")
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
            .describe("Hostname-scan detection, bans and the fail2ban log")
            .icon("shield");

        public static final SettingDefinition<Boolean> LOG_DOMAIN_MISSES = GROUP.buildSetting("log_domain_misses", Boolean.class)
            .defaultValue(true)
            .description("Log unmatched domain requests for fail2ban")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_THRESHOLD = GROUP.buildSetting("domain_miss_threshold", Integer.class)
            .defaultValue(5)
            .description("Domain misses before logging for fail2ban")
            .build();

        public static final SettingDefinition<String> DOMAIN_MISSES_LOG_PATH = GROUP.buildSetting("domain_misses_log_path", String.class)
            .defaultValue("/var/log/hohenheim/domain-misses.log")
            .description("Path for domain miss log file (fail2ban)")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_WINDOW_SECONDS = GROUP.buildSetting("domain_miss_window_seconds", Integer.class)
            .defaultValue(300)
            .description("Sliding window for counting domain misses towards a ban")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_BAN_THRESHOLD = GROUP.buildSetting("domain_miss_ban_threshold", Integer.class)
            .defaultValue(25)
            .description("In-window domain misses before an IP is banned")
            .build();

        public static final SettingDefinition<Integer> DOMAIN_MISS_DECAY_PER_HIT = GROUP.buildSetting("domain_miss_decay_per_hit", Integer.class)
            .defaultValue(2)
            .description("Misses forgiven for each successful route hit")
            .build();

        public static final SettingDefinition<Boolean> LOG_PATH_AND_UA = GROUP.buildSetting("log_path_and_ua", Boolean.class)
            .defaultValue(true)
            .description("Append request path and user agent to domain miss log lines")
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
            .description("Lifetime of a proxy-auth session (seconds)")
            .build();

        public static final SettingDefinition<Long> PERSISTENT_TTL_SECONDS = GROUP.buildSetting("persistent_ttl_seconds", Long.class)
            .defaultValue(1209600L)
            .description("Lifetime of a proxy-auth persistent (remember-me) cookie (seconds)")
            .build();
    }
}
