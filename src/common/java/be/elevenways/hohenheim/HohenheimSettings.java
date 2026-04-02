package be.elevenways.hohenheim;

import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.setting.SettingContext;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.setting.SettingGroup;

/**
 * All Hohenheim configuration settings, organized by group.
 */
public class HohenheimSettings {

    public static final SettingContext VALUES = new SettingContext.Simple(Zenit.SETTINGS);

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

    // --- Admin Interface ---
    public abstract class Admin {
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("admin");

        public static final SettingDefinition<Integer> PORT = GROUP.buildSetting("port", Integer.class)
            .defaultValue(2999)
            .description("Admin interface listen port")
            .build();
    }

    // --- Logging ---
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

    // --- Database ---
    public abstract class Database {
        public static final SettingGroup GROUP = Zenit.SETTINGS.createGroup("database");

        public static final SettingDefinition<String> PATH = GROUP.buildSetting("path", String.class)
            .defaultValue("hohenheim.db")
            .description("SQLite database file path")
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
}
