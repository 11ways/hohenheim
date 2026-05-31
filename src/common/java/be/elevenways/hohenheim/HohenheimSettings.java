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

    // AIDEV-NOTE: Rooted at Zenit.SETTINGS (like ServerSettings, unlike a typical
    // consumer that roots at its own subtree) because Hohenheim's groups (proxy,
    // ssl, ...) are top-level, matching the flat .dry config schema. ServerMain
    // loads this context from the same default.dry/local.dry sources.
    public static final SettingsContext VALUES = new SettingsContext(Zenit.SETTINGS);

    // Nested groups below are force-loaded at compile time via @ZenitAutoLoad
    // (loadInnerClasses=true): Protoblast's Gradle plugin emits a reference to
    // each into the generated BlastAutoLoadInit, fired on first Blast use. No
    // per-group boilerplate here; adding a group is enough.

    // --- Proxy ---
    public abstract class Proxy {
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("proxy");

        public static final SettingDefinition<Integer> HTTP_PORT = GROUP.buildSetting("http_port", Integer.class)
            .defaultValue(80)
            .description("HTTP proxy listen port")
            .build();

        public static final SettingDefinition<Integer> HTTPS_PORT = GROUP.buildSetting("https_port", Integer.class)
            .defaultValue(443)
            .description("HTTPS proxy listen port")
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
            .build();

        public static final SettingDefinition<String> NOT_FOUND_MESSAGE = GROUP.buildSetting("not_found_message", String.class)
            .defaultValue("There is no site configured for this domain.")
            .description("Error message for unmatched domains")
            .build();

        public static final SettingDefinition<String> UNREACHABLE_MESSAGE = GROUP.buildSetting("unreachable_message", String.class)
            .defaultValue("The upstream server is not responding.")
            .description("Error message for unreachable upstreams")
            .build();
    }

    // --- SSL/TLS ---
    public abstract class Ssl {
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("ssl");

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
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("logging");

        public static final SettingDefinition<Boolean> ACCESS_TO_DATABASE = GROUP.buildSetting("access_to_database", Boolean.class)
            .defaultValue(false)
            .description("Log access requests to database")
            .build();

        public static final SettingDefinition<Boolean> ACCESS_TO_FILE = GROUP.buildSetting("access_to_file", Boolean.class)
            .defaultValue(true)
            .description("Log access requests to file")
            .build();

        public static final SettingDefinition<String> ACCESS_PATH = GROUP.buildSetting("access_path", String.class)
            .defaultValue("/var/log/hohenheim/access.log")
            .description("Access log file path")
            .build();

        public static final SettingDefinition<Boolean> COLLECT_STATS = GROUP.buildSetting("collect_stats", Boolean.class)
            .defaultValue(true)
            .description("Collect per-site traffic statistics")
            .build();
    }

    // --- Storage ---
    public abstract class Storage {
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("storage");

        public static final SettingDefinition<String> DATA_PATH = GROUP.buildSetting("data_path", String.class)
            .defaultValue("data")
            .description("Base directory for persistent data (git repos, etc.)")
            .build();
    }

    // --- Database ---
    public abstract class Database {
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("database");

        public static final SettingDefinition<String> PATH = GROUP.buildSetting("path", String.class)
            .defaultValue("hohenheim.db")
            .description("SQLite database file path")
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
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("security");

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
    }

    // --- Proteus SSO (optional; password login is always available) ---
    public abstract class AuthProteus {
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("auth_proteus");

        public static final SettingDefinition<Boolean> ENABLED = GROUP.buildSetting("enabled", Boolean.class)
            .defaultValue(false)
            .description("Offer Proteus SSO as a login option")
            .build();

        public static final SettingDefinition<String> ENDPOINT = GROUP.buildSetting("endpoint", String.class)
            .description("Proteus realm server URL")
            .build();

        public static final SettingDefinition<String> REALM_CLIENT = GROUP.buildSetting("realm_client", String.class)
            .description("Proteus realm client slug")
            .build();

        public static final SettingDefinition<String> ACCESS_KEY = GROUP.buildSetting("access_key", String.class)
            .description("Proteus realm access key")
            .build();

        public static final SettingDefinition<String> AUTHENTICATOR = GROUP.buildSetting("authenticator", String.class)
            .defaultValue("password")
            .description("Proteus authenticator slug")
            .build();
    }
}
