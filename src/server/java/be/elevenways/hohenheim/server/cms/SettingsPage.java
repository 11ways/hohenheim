package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Runtime settings editor. The POST is the host-declared settings-update
 * endpoint, which persists edits into settings/local.dry via DrySettingsWriter.
 */
public final class SettingsPage extends PanelPage {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "settings"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("hohenheim.settings.title"); }
    @Override public @NonNull String slug() { return "settings"; }
    @Override public @NonNull NavGroup navGroup() { return NavGroup.SYSTEM; }
    @Override public int navOrder() { return 80; }
    @Override public @NonNull Icon icon() { return Icon.of("gear"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext accessContext) {
        String saved = conduit.getQueryParam("saved");
        String error = conduit.getQueryParam("error");
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/settings"),
            buildSettingsVars(saved != null ? saved : "", error != null ? error : ""));
    }

    /** Current values for the settings form (also used by the POST handler on rerender). */
    public static @NonNull Map<String, Object> buildSettingsVars(@NonNull String saved, @NonNull String error) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", "Settings");
        vars.put("saved", saved);
        vars.put("error", error);
        vars.put("proxyHttpPort", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTP_PORT));
        vars.put("proxyHttpsPort", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.HTTPS_PORT));
        vars.put("proxyFallback", orEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FALLBACK_ADDRESS)));
        vars.put("proxyForceHttps", HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
        vars.put("proxyIpv6Address", orEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.IPV6_ADDRESS)));
        // Read-only: the admin listener is Zenit's HTTP server.
        vars.put("adminPort", ServerSettings.VALUES.getValue(ServerSettings.Network.PORT));
        vars.put("dbPath", HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH));
        vars.put("logAccessToDb", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_DATABASE));
        vars.put("logAccessToFile", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_TO_FILE));
        vars.put("logAccessPath", HohenheimSettings.VALUES.getValue(HohenheimSettings.Logging.ACCESS_PATH));
        vars.put("secLogDomainMisses", HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.LOG_DOMAIN_MISSES));
        vars.put("secDomainMissThreshold", HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.DOMAIN_MISS_THRESHOLD));
        vars.put("sslLeEnabled", HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED));
        vars.put("sslLeEmail", orEmpty(HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_EMAIL)));
        vars.put("sslLeStaging", HohenheimSettings.VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_STAGING));
        return vars;
    }

    private static @NonNull String orEmpty(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
