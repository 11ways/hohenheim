package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.net.OwnRequestHeaders;
import be.elevenways.protoblast.common.util.BlastLog;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Hohenheim's own request headers ({@link OwnRequestHeaders}) on the forward path: found in one pass over a request's
 * header names ({@link #scan}), and removed, every one, from every forwarded request ({@link #remove}).
 *
 * AIDEV-NOTE: no sender and no route keeps a member: a request from a linked server loses the family like any
 * visitor's. A trusted front's X-Hohenheim-Key is its credential, consumed by ProxyScheme and removed by
 * ForwardingHeaders itself, so it is neither the visitor's nor part of this pass.
 *
 * AIDEV-NOTE: a request carrying no member (all normal traffic) costs the one pass and nothing else: no allocation,
 * no other lookup. Only a request that carries one is logged: once per site per minute at most
 * (BlastLog.distinctProblem keyed by the site), naming the header names, never a value.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.10.0
 */
final class OwnHeaders {

    /** How often a site's line may be written. */
    static final long LOG_INTERVAL_MS = 60_000;

    private OwnHeaders() {
    }

    /**
     * The one pass over the request's header names, before any header rule touches them.
     *
     * @return the members it carried, as spelled; null for a request carrying none
     */
    static @Nullable List<String> scan(@NonNull HttpServerExchange exchange) {
        HeaderMap headers = exchange.getRequestHeaders();
        List<String> names = null;
        for (long cookie = headers.fastIterateNonEmpty(); cookie != -1L; cookie = headers.fiNextNonEmpty(cookie)) {
            HttpString name = headers.fiCurrent(cookie).getHeaderName();
            if (!isOwn(name)
                    || (ProxyScheme.X_HOHENHEIM_KEY.equals(name) && ProxyScheme.isTrustedRemoteProxy(exchange))) {
                continue;
            }
            if (names == null) {
                names = new ArrayList<>();
            }
            names.add(name.toString());
        }
        return names == null ? null : List.copyOf(names);
    }

    /** {@link OwnRequestHeaders#isOwn} on the name's bytes as Undertow holds them, so nothing is allocated. */
    private static boolean isOwn(@NonNull HttpString name) {
        int length = OwnRequestHeaders.PREFIX.length();
        if (name.length() < length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (!OwnRequestHeaders.prefixAt(i, (char) (name.byteAt(i) & 0xFF))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Remove every member the request carried and log what went.
     *
     * @param names what {@link #scan} found, null for none
     */
    static void remove(@NonNull HeaderMap headers, @Nullable List<String> names, @NonNull RouteEntry entry,
                       @NonNull String clientIp) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            headers.remove(name);
        }
        // One message in an array, so the interval overload is the one the call names (not the varargs one).
        BlastLog.distinctProblem("hohenheim.proxy.own_headers:" + entry.siteId, LOG_INTERVAL_MS, new Object[] {
            "Proxy: removed Hohenheim's own headers a visitor sent to site '" + entry.siteName + "' from " + clientIp
                + ": " + String.join(", ", names)});
    }
}
