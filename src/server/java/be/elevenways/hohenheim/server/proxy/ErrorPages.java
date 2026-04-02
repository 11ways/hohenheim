package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

/**
 * Styled HTML error responses for the reverse proxy.
 */
class ErrorPages {

    private static final String STYLE = """
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                   background: #0a0a0a; color: #e5e5e5; display: flex; align-items: center;
                   justify-content: center; min-height: 100vh; }
            .error { text-align: center; max-width: 480px; padding: 2rem; }
            .error-code { font-size: 6rem; font-weight: 700; color: #525252; line-height: 1; }
            .error-title { font-size: 1.25rem; font-weight: 600; margin: 1rem 0 0.5rem; }
            .error-message { color: #a3a3a3; font-size: 0.875rem; line-height: 1.5; }
            .error-host { color: #737373; font-size: 0.75rem; margin-top: 2rem;
                          padding-top: 1rem; border-top: 1px solid #262626; }
        </style>
        """;

    static void send404(HttpServerExchange exchange, String hostname) {
        String msg = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.NOT_FOUND_MESSAGE);
        exchange.setStatusCode(404);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
            + "<title>404 - Not Found</title>" + STYLE + "</head><body>"
            + "<div class=\"error\">"
            + "<div class=\"error-code\">404</div>"
            + "<div class=\"error-title\">No site configured</div>"
            + "<div class=\"error-message\">" + escapeHtml(msg) + "</div>"
            + "<div class=\"error-host\">" + escapeHtml(hostname) + "</div>"
            + "</div></body></html>");
    }

    static void send502(HttpServerExchange exchange, String message) {
        String msg = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.UNREACHABLE_MESSAGE);
        exchange.setStatusCode(502);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
        exchange.getResponseSender().send("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
            + "<title>502 - Bad Gateway</title>" + STYLE + "</head><body>"
            + "<div class=\"error\">"
            + "<div class=\"error-code\">502</div>"
            + "<div class=\"error-title\">Bad Gateway</div>"
            + "<div class=\"error-message\">" + escapeHtml(msg) + "</div>"
            + "</div></body></html>");
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
