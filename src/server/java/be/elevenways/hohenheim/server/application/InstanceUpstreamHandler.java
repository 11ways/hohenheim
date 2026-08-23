package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URI;

/**
 * Serves a hostname from the SERVING RELEASE of the application the site names -- the
 * {@code instance} upstream kind's dispatch.
 *
 * AIDEV-NOTE: this handler CONVERGES NOTHING, and that is the behaviour change the
 * re-keying bought. Its predecessor ({@code DockerSiteRequestHandler}) ran the whole release
 * engine inside its constructor, so a routing reload could block on a sandbox build and a
 * site with a broken source went down on an unrelated edit. Deploying is now the
 * application's own verb ({@link ApplicationDeploys}); routing only ever RESOLVES.
 *
 * AIDEV-NOTE: the resolved address is re-read whenever the application's generation moves
 * ({@link ApplicationUpstreams}), which is what makes a flip visible to a handler that was
 * built generations ago. Caching the URI without that check is exactly the stale-address
 * defect -- traffic kept going to the container the drain was about to stop.
 *
 * AIDEV-NOTE: a NULL resolution additionally expires on a short timer. The generation check
 * alone makes "nothing is serving" permanent for anything this JVM did not do itself -- a
 * site whose routes were built while its workspace was down answered 503 until a restart,
 * and on a multi-controller estate a deploy driven by the OTHER controller moves no
 * generation here at all. Only the negative case is retried: it costs at most one resolution
 * per interval while the site is already down, whereas expiring a held ADDRESS would put a
 * database read on the request path this cache exists to keep off it.
 */
public final class InstanceUpstreamHandler implements SiteRequestHandler {

    /** How long a "nothing is serving" answer is trusted before it is asked again. */
    private static final long NEGATIVE_RETRY_NANOS = 2_000_000_000L;

    private final int siteId;
    private final int applicationId;
    private final boolean websocketEnabled;
    private final String scheme;

    private volatile ApplicationUpstreams.Resolution resolution;

    /** When the held resolution was taken (nanoTime); read only while that answer is NULL. */
    private volatile long negativeSince;

    public InstanceUpstreamHandler(int siteId, int applicationId) {
        this(siteId, applicationId, true, "http");
    }

    /**
     * @param websocketEnabled the site's {@code websocket_upgrade} setting; an upgrade
     *        request is refused when it is off, the same rule the address kind applies
     * @param scheme the site's declared {@code scheme}, for a workload terminating TLS
     */
    public InstanceUpstreamHandler(int siteId, int applicationId, boolean websocketEnabled,
                                   @NonNull String scheme) {
        this.siteId = siteId;
        this.applicationId = applicationId;
        this.websocketEnabled = websocketEnabled;
        this.scheme = scheme;
        hold(ApplicationUpstreams.resolve(applicationId, scheme));
    }

    @Override
    public int getSiteId() {
        return this.siteId;
    }

    /** The application whose serving release this site exposes. */
    public int getApplicationId() {
        return this.applicationId;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {

        if (!this.websocketEnabled
                && exchange.getRequestHeaders().contains(Headers.UPGRADE)) {
            exchange.setStatusCode(403);
            exchange.getResponseSender().send("WebSocket upgrades disabled for this site");
            return;
        }

        URI target = current();

        if (target == null) {
            exchange.setStatusCode(503);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("No release is serving this application");
            return;
        }

        forwarder.forwardTo(new UpstreamTarget(target, false));
    }

    @Override
    public SiteHealth getHealth() {
        return current() == null ? SiteHealth.DOWN : SiteHealth.UP;
    }

    /** The upstream this request must go to, re-resolved when the application flipped. */
    public @Nullable URI current() {
        ApplicationUpstreams.Resolution held = this.resolution;
        boolean expiredNegative = held.upstream() == null
            && System.nanoTime() - this.negativeSince >= NEGATIVE_RETRY_NANOS;
        if (expiredNegative
                || held.generation() != ApplicationUpstreams.generationOf(this.applicationId)) {
            held = ApplicationUpstreams.resolve(this.applicationId, this.scheme);
            hold(held);
        }
        return held.upstream();
    }

    /** Keep a resolution together with the moment it was taken. */
    private void hold(ApplicationUpstreams.@NonNull Resolution resolved) {
        this.negativeSince = System.nanoTime();
        this.resolution = resolved;
    }
}
