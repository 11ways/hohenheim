package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hawkeye.common.Hawkeye;
import be.elevenways.hawkeye.common.render.RenderBlock;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders error responses for the reverse proxy using Hawkeye templates.
 */
class ErrorPages {

    private static final Identifier ERROR_TEMPLATE =
        Identifier.of("hohenheim", "hohenheim/error");

    static void send404(HttpServerExchange exchange, String hostname) {
        String msg = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.NOT_FOUND_MESSAGE);
        String html = render("404", "No site configured", msg, hostname);

        exchange.setStatusCode(404);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send(html);
    }

    static void send502(HttpServerExchange exchange, String message) {
        String msg = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.UNREACHABLE_MESSAGE);
        String html = render("502", "Bad Gateway", msg, null);

        exchange.setStatusCode(502);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send(html);
    }

    private static String render(String statusCode, String title, String message, String hostname) {
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
