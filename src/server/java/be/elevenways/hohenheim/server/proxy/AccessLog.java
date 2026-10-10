package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.time.Now;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Proxy access logging in the Node original's line format; best-effort, a logging failure never breaks request
 * handling.
 *
 * AIDEV-NOTE: the line is the original's, so parsers written for the Node Hohenheim keep working:
 * {@code host: ip - - [dd/MMM/yyyy:HH:mm:ss Z] "METHOD path PROTO" status size "referer" "ua"}. The rewrite had
 * moved the vhost into the referer slot and dropped the Referer.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class AccessLog {

    /** The original's {@code d/M/Y:H:i:s O} timestamp, in the host's zone. */
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private static final LogFile FILE = new LogFile();

    /** Register a completion listener that appends one access-log line once the response is sent. */
    public void logAccess(HttpServerExchange exchange, String hostname, String clientIp) {
        if (!HohenheimSettings.isOn(HohenheimSettings.Logging.ACCESS_TO_FILE)) {
            return;
        }

        // The path the CLIENT asked for, captured now: strip_path rewrites the exchange's path
        // before the completion listener runs.
        RequestPath requestPath = RequestPath.of(exchange);
        String path = requestPath != null ? requestPath.raw() : RequestPath.rawPathOf(exchange);

        exchange.addExchangeCompleteListener((ex, next) -> {
            try {
                FILE.appendTo(HohenheimSettings.Logging.ACCESS_PATH, line(ex, hostname, clientIp, path));
            } catch (Exception ignored) {
                // Don't let logging break request handling
            }
            next.proceed();
        });
    }

    /** One access-log line; every client-controlled field is escaped so no field can end early or forge a line. */
    static String line(HttpServerExchange ex, String hostname, String clientIp, String path) {
        String query = ex.getQueryString();
        return LogFile.escape(hostname) + ": " + LogFile.escape(clientIp) + " - - ["
            + TIMESTAMP.format(Now.instant().atZone(ZoneId.systemDefault())) + "] \""
            + LogFile.escape(ex.getRequestMethod().toString()) + " " + LogFile.escape(path)
            + (query != null && !query.isEmpty() ? "?" + LogFile.escape(query) : "")
            + " " + LogFile.escape(String.valueOf(ex.getProtocol())) + "\" " + ex.getStatusCode()
            + " " + ex.getResponseBytesSent()
            + " \"" + LogFile.quote(ex.getRequestHeaders().getFirst(Headers.REFERER)) + "\""
            + " \"" + LogFile.quote(ex.getRequestHeaders().getFirst(Headers.USER_AGENT)) + "\"";
    }
}
