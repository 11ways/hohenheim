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
            Map<String, Object> vars = new HashMap<>();
            String saved = conduit.getQueryParam("saved");
            vars.put("saved", saved != null ? saved : "");
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

            return new RenderTemplateResult(
                Identifier.of("hohenheim", "hohenheim/settings"),
                vars
            );
        });

        HohenheimEndpoints.SETTINGS_UPDATE.setHandler(conduit -> {
            Map<String, String> form = HandlerSupport.formMap(conduit);

            // Proxy settings
            String httpPort = form.get("proxy_http_port");
            if (httpPort != null) {
                try { HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, Integer.parseInt(httpPort)); }
                catch (NumberFormatException ignored) {}
            }
            String httpsPort = form.get("proxy_https_port");
            if (httpsPort != null) {
                try { HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, Integer.parseInt(httpsPort)); }
                catch (NumberFormatException ignored) {}
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
                form.getOrDefault("log_access_path", "/var/log/hohenheim/access.log"));
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Logging.COLLECT_STATS,
                form.containsKey("log_collect_stats"));

            // Security settings
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES,
                form.containsKey("sec_log_domain_misses"));
            String threshold = form.get("sec_domain_miss_threshold");
            if (threshold != null) {
                try { HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD, Integer.parseInt(threshold)); }
                catch (NumberFormatException ignored) {}
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
}
