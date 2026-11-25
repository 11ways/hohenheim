package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.traffic.HohenheimTraffic;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.traffic.common.TrafficHit;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Counts each completed proxied request into the proxy's traffic store, once its route is chosen.
 *
 * AIDEV-NOTE: one hit per exchange, under the source SiteDispatcher.answersFor decides at the route's choice, the
 * decision the access log line takes too: a name nobody serves is counted as unserved, even when the unknown-host
 * answer hands it to one of the operator's sites. Every answer after that point is counted: a wrong-path 404 of a
 * known name (under the site serving the name), an HTTPS or a site's own redirect, a sign-in, access-list or
 * protected-path refusal, and the upstream's answer. The answers before it belong to no site and are not counted: a
 * refused front-proxy key, a malformed path, an ACME challenge, a banned visitor, a git or build hook and a proxy
 * loop. Recording never blocks and never fails a request: the store's queue drops and counts a hit it has no room
 * for. The response time is AccessLog's one reading of the exchange's stopwatch, the access log's {@code time_ms}.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class ProxyTraffic {

    private static final AttachmentKey<Boolean> RECORDED = AttachmentKey.create(Boolean.class);

    private ProxyTraffic() {
    }

    /** Counts the exchange under the source once it completes; an exchange already counted is not counted again. */
    static void record(@NonNull HttpServerExchange exchange, @NonNull String source, @NonNull String hostname,
                       @NonNull String clientIp) {
        if (exchange.getAttachment(RECORDED) != null) {
            return;
        }
        try {
            exchange.putAttachment(RECORDED, Boolean.TRUE);
            String path = RequestPath.askedOf(exchange);
            String method = exchange.getRequestMethod().toString();
            long received = exchange.getRequestContentLength();
            String userAgent = exchange.getRequestHeaders().getFirst(Headers.USER_AGENT);
            String referrer = exchange.getRequestHeaders().getFirst(Headers.REFERER);
            exchange.addExchangeCompleteListener((completed, next) -> {
                try {
                    HohenheimTraffic.count(HohenheimTraffic.Counted.WEB, new TrafficHit(Now.instant(), source,
                        hostname, path, method, completed.getStatusCode(), completed.getResponseBytesSent(),
                        received, AccessLog.tookNanos(completed) / 1000, clientIp, userAgent, referrer));
                } catch (RuntimeException ignored) {
                    // Counting traffic never breaks a request.
                }
                next.proceed();
            });
        } catch (RuntimeException ignored) {
            // Counting traffic never breaks a request.
        }
    }
}
