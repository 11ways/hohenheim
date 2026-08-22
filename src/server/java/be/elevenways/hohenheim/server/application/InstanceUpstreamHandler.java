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
 */
public final class InstanceUpstreamHandler implements SiteRequestHandler {

    private final int siteId;
    private final int applicationId;
    private final boolean websocketEnabled;
    private final String scheme;

    private volatile ApplicationUpstreams.Resolution resolution;

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
        this.resolution = ApplicationUpstreams.resolve(applicationId, scheme);
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
        if (held.generation() != ApplicationUpstreams.generationOf(this.applicationId)) {
            held = ApplicationUpstreams.resolve(this.applicationId, this.scheme);
            this.resolution = held;
        }
        return held.upstream();
    }
}
