package be.elevenways.hohenheim.server.handlers;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.server.setting.ServerSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Settings request handlers.
 */
public final class SettingsHandlers {

    private SettingsHandlers() {
    }

    public static void init() {
        HohenheimEndpoints.SETTINGS.setHandler(conduit -> {
            String saved = conduit.getQueryParam("saved");
            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/settings"),
                buildSettingsVars(saved != null ? saved : "", "")
            );
        });

        HohenheimEndpoints.SETTINGS_UPDATE.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);

            // Validate everything before writing anything, so a typo'd value
            // renders an error instead of silently keeping the old setting.
            Integer httpPort = parseBoundedInt(form.get("proxy_http_port"), 1, 65535);
            Integer httpsPort = parseBoundedInt(form.get("proxy_https_port"), 1, 65535);
            Integer threshold = parseBoundedInt(form.get("sec_domain_miss_threshold"), 1, Integer.MAX_VALUE);

            String error = null;
            if (form.get("proxy_http_port") != null && httpPort == null) {
                error = "Proxy HTTP port must be a number between 1 and 65535.";
            } else if (form.get("proxy_https_port") != null && httpsPort == null) {
                error = "Proxy HTTPS port must be a number between 1 and 65535.";
            } else if (form.get("sec_domain_miss_threshold") != null && threshold == null) {
                error = "Domain-miss ban threshold must be a positive number.";
            }
            if (error != null) {
                return HandlerSupport.renderUntyped(
                    Identifier.of("hohenheim", "hohenheim/settings"),
                    buildSettingsVars("", error)
                );
            }

            // Proxy settings
            if (httpPort != null) {
                HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, httpPort);
            }
            if (httpsPort != null) {
                HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, httpsPort);
            }
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS,
                form.getOrDefault("proxy_fallback", ""));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS,
                form.containsKey("proxy_force_https"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.IPV6_ADDRESS,
                form.getOrDefault("proxy_ipv6_address", "").trim());

            // Logging settings
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_DATABASE,
                form.containsKey("log_access_to_db"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_TO_FILE,
                form.containsKey("log_access_to_file"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.ACCESS_PATH,
                form.getOrDefault("log_access_path",
                    HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH)));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.COLLECT_STATS,
                form.containsKey("log_collect_stats"));

            // Security settings
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES,
                form.containsKey("sec_log_domain_misses"));
            if (threshold != null) {
                HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD, threshold);
            }

            // SSL settings
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED,
                form.containsKey("ssl_le_enabled"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL,
                form.getOrDefault("ssl_le_email", ""));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_STAGING,
                form.containsKey("ssl_le_staging"));

            return HandlerSupport.redirectUntyped("/settings?saved=true");
        });
    }

    /** @return the parsed value when it lies in [min, max], or null for blank/garbage/out-of-range */
    private static Integer parseBoundedInt(String raw, int min, int max) {
        if (raw == null) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= min && value <= max ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> buildSettingsVars(String saved, String error) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("saved", saved);
        vars.put("error", error);
        vars.put("proxyHttpPort", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT));
        vars.put("proxyHttpsPort", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT));
        vars.put("proxyFallback", HandlerSupport.valueOrEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS)));
        vars.put("proxyForceHttps", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
        vars.put("proxyIpv6Address", HandlerSupport.valueOrEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.IPV6_ADDRESS)));
        // Read-only: the admin listener is Zenit's HTTP server, whose port
        // is ServerSettings.Network.PORT. Surfaced here so operators can
        // see where the admin UI is reachable.
        vars.put("adminPort", ServerSettings.VALUES.getValue(ServerSettings.Network.PORT));
        vars.put("dbPath", HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH));
        vars.put("logAccessToDb", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_DATABASE));
        vars.put("logAccessToFile", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE));
        vars.put("logAccessPath", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH));
        vars.put("logCollectStats", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.COLLECT_STATS));
        vars.put("secLogDomainMisses", HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES));
        vars.put("secDomainMissThreshold", HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD));
        vars.put("sslLeEnabled", HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED));
        vars.put("sslLeEmail", HandlerSupport.valueOrEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL)));
        vars.put("sslLeStaging", HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_STAGING));
        return vars;
    }
}
