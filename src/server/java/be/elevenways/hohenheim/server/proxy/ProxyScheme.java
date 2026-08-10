package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.server.http.RequestScheme;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * THE effective client-facing scheme for anything holding a raw proxy exchange: cookie
 * Secure flags, force-SSL redirects, HSTS emission, the upstream X-Forwarded-Proto and
 * any absolute URL handed back to the browser. The raw {@code exchange.getRequestScheme()}
 * is never the answer -- it is correct only while hohenheim terminates TLS itself and
 * silently downgrades the moment it sits behind a terminator.
 *
 * AIDEV-NOTE: this listener faces the open internet, so its socket peer is an arbitrary
 * client and IP-based trust is NOT the boundary here: a forwarded proto counts only from
 * a peer authenticated by {@code X-Hohenheim-Key} (proxy.trusted_proxy_keys, hohenheim's
 * own federation lane). Everything else answers from zenit's {@link RequestScheme} with
 * NO forwarded headers offered, i.e. direct TLS or the operator's explicit
 * {@code network.assume_https} declaration -- deliberately narrower than the
 * {@code network.trusted_proxies} lane a zenit app listener uses.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProxyScheme {

    static final HttpString X_HOHENHEIM_KEY = new HttpString("X-Hohenheim-Key");
    static final HttpString X_FORWARDED_PROTO = new HttpString("X-Forwarded-Proto");

    // AIDEV-NOTE: resolved ONCE per exchange and attached, because the dispatcher strips
    // X-Hohenheim-Key and rewrites X-Forwarded-Proto mid-pipeline: any later reader asking
    // the raw headers again would silently get "http" for a request that arrived over TLS.
    private static final AttachmentKey<Boolean> RESOLVED = AttachmentKey.create(Boolean.class);

    // One reference so a reader can never see a new source paired with the old key set.
    private record ParsedKeys(@Nullable List<String> source, Set<String> keys) {}

    private static volatile ParsedKeys parsedKeys = new ParsedKeys(null, Set.of());

    /**
     * Resolve and pin the decision for this exchange; call once, before any handler can
     * mutate the trust-boundary headers.
     */
    public static boolean resolve(@NonNull HttpServerExchange exchange) {
        Boolean resolved = exchange.getAttachment(RESOLVED);
        if (resolved != null) {
            return resolved;
        }

        boolean https = derive(exchange);
        exchange.putAttachment(RESOLVED, https);
        return https;
    }

    /** True when the client reached the front of this proxy chain over HTTPS. */
    public static boolean isEffectivelyHttps(@NonNull HttpServerExchange exchange) {
        return resolve(exchange);
    }

    private static boolean derive(@NonNull HttpServerExchange exchange) {
        if (isTrustedRemoteProxy(exchange)) {
            String proto = exchange.getRequestHeaders().getFirst(X_FORWARDED_PROTO);
            if (proto != null && !proto.isBlank()) {
                return "https".equalsIgnoreCase(proto.trim());
            }
        }

        return RequestScheme.isEffectivelyHttps(exchange.isSecure(), null, null, null);
    }

    /** The value to forward upstream in X-Forwarded-Proto. */
    public static @NonNull String effectiveScheme(@NonNull HttpServerExchange exchange) {
        return isEffectivelyHttps(exchange) ? "https" : "http";
    }

    /**
     * Whether this peer presented a configured X-Hohenheim-Key, which authorizes it to
     * state the original client's IP and proto (hohenheim-to-hohenheim federation).
     */
    public static boolean isTrustedRemoteProxy(@NonNull HttpServerExchange exchange) {
        String key = exchange.getRequestHeaders().getFirst(X_HOHENHEIM_KEY);
        if (key == null) {
            return false;
        }
        // AIDEV-NOTE: constant-time compare against every configured key, matching the rest of
        // the codebase's secret handling -- a HashSet.contains (String.equals) leaks a prefix
        // match through timing. Every candidate is checked with no early-out so the loop's
        // duration does not reveal WHICH key matched either.
        byte[] presented = key.getBytes(StandardCharsets.UTF_8);
        boolean match = false;
        for (String candidate : trustedProxyKeys()) {
            if (MessageDigest.isEqual(presented, candidate.getBytes(StandardCharsets.UTF_8))) {
                match = true;
            }
        }
        return match;
    }

    /**
     * AIDEV-NOTE: re-parsed when the setting VALUE changes, never on a timer -- a TTL leaves
     * a window in which a revoked key still authenticates, and it made the key set depend on
     * wall-clock timing rather than configuration.
     */
    private static @NonNull Set<String> trustedProxyKeys() {
        List<String> configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS);

        ParsedKeys current = parsedKeys;
        if (configured == null || configured.isEmpty()) {
            return Set.of();
        }
        if (configured.equals(current.source())) {
            return current.keys();
        }

        Set<String> keys = new HashSet<>();
        for (String part : configured) {
            if (!part.isBlank()) {
                keys.add(part.trim());
            }
        }

        ParsedKeys parsed = new ParsedKeys(List.copyOf(configured), Set.copyOf(keys));
        parsedKeys = parsed;
        return parsed.keys();
    }

    private ProxyScheme() {}
}
