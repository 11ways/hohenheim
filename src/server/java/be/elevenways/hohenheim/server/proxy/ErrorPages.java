package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hawkeye.common.Hawkeye;
import be.elevenways.hawkeye.common.render.RenderBlock;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.server.http.AcceptLanguageMiddleware;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders error responses for the reverse proxy using Hawkeye templates.
 *
 * AIDEV-NOTE: these pages ARE localized, and they are the only public-facing ones in the
 * product -- an anonymous visitor to a misconfigured domain sees them. There is no
 * Conduit here (the proxy answers on the raw Undertow exchange, before any Zenit
 * request), so the locale chain is negotiated from the request's own Accept-Language
 * through the SAME parser the framework middleware uses, falling back to the site's
 * default content locale. The operator-authored {@code proxy.*_message} settings still
 * WIN when set: their whole point is a site owner's own copy, which no catalog can
 * translate. Blank (the shipped default) means "use the localized text".
 */
public final class ErrorPages {

    private static final Identifier ERROR_TEMPLATE =
        Identifier.of("hohenheim", "hohenheim/error");

    /** The microcopy scope every proxy error string lives in. */
    private static final String SCOPE = "proxy_error";

    static void send404(HttpServerExchange exchange, String hostname) {
        LocaleChain locales = localesOf(exchange);
        String html = render("404", text(locales, "not_found_title"),
            override(HohenheimSettings.Proxy.NOT_FOUND_MESSAGE, locales, "not_found_message"),
            hostname);

        exchange.setStatusCode(404);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send(html);
    }

    /** 503 for a dev-namespace subdomain with no live registration. */
    public static void sendDevOffline(HttpServerExchange exchange, String name) {
        LocaleChain locales = localesOf(exchange);
        String html = render("503", text(locales, "dev_offline_title"),
            Microcopy.of("dev_offline_message").withFilter("scope", SCOPE)
                .withArg("name", name)
                .resolve(locales, Zenit.getMessageResolver()),
            name);

        exchange.setStatusCode(503);
        exchange.getResponseHeaders().put(Headers.RETRY_AFTER, "5");
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send(html);
    }

    /** 503 for a force-SSL route while HTTPS termination is down: refuse, never serve cleartext. */
    static void sendHttpsRequired(HttpServerExchange exchange, String hostname) {
        LocaleChain locales = localesOf(exchange);
        String html = render("503", text(locales, "https_required_title"),
            text(locales, "https_required_message"), hostname);

        exchange.setStatusCode(503);
        exchange.getResponseHeaders().put(Headers.RETRY_AFTER, "30");
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send(html);
    }

    static void send502(HttpServerExchange exchange, String message) {
        LocaleChain locales = localesOf(exchange);
        String html = render("502", text(locales, "bad_gateway_title"),
            override(HohenheimSettings.Proxy.UNREACHABLE_MESSAGE, locales,
                "unreachable_message"),
            null);

        exchange.setStatusCode(502);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send(html);
    }

    /** The visitor's negotiated locale chain; a header-less client gets the site default. */
    private static LocaleChain localesOf(HttpServerExchange exchange) {
        LocaleChain chain = AcceptLanguageMiddleware.parse(
            exchange.getRequestHeaders().getFirst(Headers.ACCEPT_LANGUAGE));
        return chain.isEmpty() ? LocaleChain.of(ContentLocales.getDefault()) : chain;
    }

    /** An argument-less proxy-error string. */
    private static String text(LocaleChain locales, String key) {
        return Microcopy.of(key).withFilter("scope", SCOPE)
            .resolve(locales, Zenit.getMessageResolver());
    }

    /** The operator's own copy when they set one, else the localized default. */
    private static String override(SettingDefinition<String> setting, LocaleChain locales,
                                  String key) {
        String configured = HohenheimSettings.VALUES.getValue(setting);
        return configured != null && !configured.isBlank() ? configured : text(locales, key);
    }

    private static String render(String statusCode, String title, String message,
                                 String hostname) {
        try {
            Hawkeye hawkeye = Zenit.getHawkeye();
            var engine = hawkeye.createRenderEngine();

            Map<String, Object> vars = new HashMap<>();
            vars.put("statusCode", statusCode);
            vars.put("title", title);
            vars.put("message", message != null ? message : "");
            vars.put("hostname", hostname != null ? hostname : "");

            RenderBlock block = engine.render(ERROR_TEMPLATE, vars);
            if (block != null) {
                return block.getAsHtml();
            }
        } catch (Exception e) {
            // Fall through to hardcoded fallback
        }

        return fallback(statusCode, title, message);
    }

    private static String fallback(String statusCode, String title, String message) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
            + "<title>" + escapeHtml(statusCode) + " - " + escapeHtml(title) + "</title></head>"
            + "<body style=\"font-family:sans-serif;text-align:center;padding:4rem\">"
            + "<h1>" + escapeHtml(statusCode) + "</h1>"
            + "<p>" + escapeHtml(title) + "</p>"
            + "<p>" + escapeHtml(message) + "</p>"
            + "</body></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
