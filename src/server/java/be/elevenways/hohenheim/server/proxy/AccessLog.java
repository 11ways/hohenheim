package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.model.ModelChanges;
import be.elevenways.zenit.common.time.OfficeZone;
import be.elevenways.zenit.server.live.LiveServer;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Proxy access logging in the Node original's line format, with the time the request took and the site that answered
 * it after the user agent; best-effort, a logging failure never breaks request handling.
 *
 * AIDEV-NOTE: the line is the original's, so parsers written for the Node Hohenheim keep working:
 * {@code host: ip - - [dd/MMM/yyyy:HH:mm:ss Z] "METHOD path PROTO" status size "referer" "ua"}, byte for byte up to
 * the user agent's closing quote. Two fields follow it, {@code time_ms=41 site=12}: the milliseconds from the request
 * reaching the proxy to its response sent, and the id of the site whose route answered, which is how
 * AccessLogReader reads one site's lines whatever addresses it had. A parser matching up to the user agent ignores
 * them; one anchored at the line's end after the agent must allow them. The rewrite had moved the vhost into the
 * referer slot and dropped the Referer.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class AccessLog {

    /** The live topic a site's watchers of its access log hear, keyed by the site's id. */
    public static final Identifier TOPIC = HohenheimIds.id("access_log");

    /** The original's {@code d/M/Y:H:i:s O} timestamp, on the office clock (the offset names it), never the host's. */
    static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    /** The name of the field after the user agent that holds the milliseconds the request took. */
    static final String TIME_FIELD = "time_ms";

    /** The name of the line's last field, the id of the site that answered. */
    static final String SITE_FIELD = "site";

    /** The site field at a line's end. */
    private static final Pattern SITE_AT_END = Pattern.compile(" " + SITE_FIELD + "=\\d+$");

    /** When the proxy took the exchange in, so the time logged covers the gates before the upstream too. */
    private static final AttachmentKey<Now.Stopwatch> STARTED = AttachmentKey.create(Now.Stopwatch.class);

    private static final LogFile FILE = new LogFile();

    /** Marks the moment the proxy took the exchange in; the logged time counts from here. */
    public static void start(@NonNull HttpServerExchange exchange) {
        exchange.putAttachment(STARTED, Now.stopwatch());
    }

    /** The time the exchange took, frozen at its first reading. */
    private static final AttachmentKey<Long> TOOK = AttachmentKey.create(Long.class);

    /**
     * The time the exchange took, from {@link #start} to the first reading at its completion; the access log's
     * {@code time_ms} and the traffic totals both read it here, so the two count the same span to the same nanosecond.
     *
     * @return the nanoseconds taken, frozen at the first call; 0 for an exchange the proxy never started
     */
    public static long tookNanos(@NonNull HttpServerExchange exchange) {
        Long took = exchange.getAttachment(TOOK);
        if (took == null) {
            Now.Stopwatch started = exchange.getAttachment(STARTED);
            took = started != null ? started.realNanos() : 0L;
            exchange.putAttachment(TOOK, took);
        }
        return took;
    }

    /** Register a completion listener that appends one access-log line once the response is sent. */
    public void logAccess(HttpServerExchange exchange, String hostname, String clientIp, int siteId) {
        if (!HohenheimSettings.isOn(HohenheimSettings.Logging.ACCESS_TO_FILE)) {
            return;
        }

        // The path the CLIENT asked for, captured now: strip_path rewrites the exchange's path
        // before the completion listener runs.
        String path = RequestPath.askedOf(exchange);

        exchange.addExchangeCompleteListener((ex, next) -> {
            try {
                long millis = TimeUnit.NANOSECONDS.toMillis(tookNanos(ex));
                FILE.appendTo(HohenheimSettings.Logging.ACCESS_PATH, line(ex, hostname, clientIp, path)
                    + fields(millis, siteId));
                // One hub lookup when nobody follows the site's log; coalesced per window when someone does.
                LiveServer.INVALIDATIONS.changed(TOPIC, ModelChanges.Keys.of(siteId));
            } catch (Exception ignored) {
                // Don't let logging break request handling
            }
            next.proceed();
        });
    }

    /** The fields after the user agent: the time taken and the answering site. */
    static @NonNull String fields(long millis, int siteId) {
        return " " + TIME_FIELD + "=" + millis + " " + SITE_FIELD + "=" + siteId;
    }

    /** @return a line as the log holds it without the site field, for a reader who already named the site */
    public static @NonNull String withoutSite(@NonNull String raw) {
        return SITE_AT_END.matcher(raw).replaceFirst("");
    }

    /** @return where the access log's live file is, as its setting names it */
    public static @NonNull Path livePath() {
        return Path.of(Objects.requireNonNullElse(Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Logging.ACCESS_PATH), ""));
    }

    /** One access-log line of a sent response, up to its user agent. */
    static String line(HttpServerExchange ex, String hostname, String clientIp, String path) {
        String query = ex.getQueryString();
        return line(hostname, clientIp, Now.instant(), ex.getRequestMethod().toString(),
            query != null && !query.isEmpty() ? path + "?" + query : path, String.valueOf(ex.getProtocol()),
            ex.getStatusCode(), ex.getResponseBytesSent(), ex.getRequestHeaders().getFirst(Headers.REFERER),
            ex.getRequestHeaders().getFirst(Headers.USER_AGENT));
    }

    /**
     * The original's line up to its user agent; every client-controlled field is escaped so no field can end early or
     * forge a line.
     *
     * @param target the path the client sent, with its query
     */
    static @NonNull String line(String hostname, String clientIp, @NonNull Instant at, String method, String target,
                                String protocol, int status, long bytes, String referer, String userAgent) {
        return LogFile.escape(hostname) + ": " + LogFile.escape(clientIp) + " - - ["
            + TIMESTAMP.format(at.atZone(OfficeZone.office())) + "] \""
            + LogFile.escape(method) + " " + LogFile.escape(target) + " " + LogFile.escape(protocol) + "\" " + status
            + " " + bytes + " \"" + LogFile.quote(referer) + "\"" + " \"" + LogFile.quote(userAgent) + "\"";
    }
}
