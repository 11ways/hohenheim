package be.elevenways.hohenheim.server.preview;

import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.net.URI;

/**
 * Serves a preview's generated hostname by proxying to the preview instance's
 * published loopback port. Owns NOTHING: the instance lifecycle belongs to
 * PreviewDeployments, so destroy() is the no-op a superseded routing generation
 * expects -- previews (re)deploy through their own funnel and trigger a reload.
 */
public class PreviewRequestHandler implements SiteRequestHandler {

    private final int siteId;
    private final int previewId;
    private final URI upstream;

    /** Resolved at routing-generation build time; a redeploy reloads the routes. */
    public PreviewRequestHandler(int siteId, int previewId) {
        this.siteId = siteId;
        this.previewId = previewId;
        this.upstream = PreviewDeployments.upstreamOf(previewId);
    }

    @Override
    public int getSiteId() {
        return siteId;
    }

    public int getPreviewId() {
        return previewId;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
        URI target = upstream;
        if (target == null) {
            exchange.setStatusCode(503);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Preview not running");
            return;
        }
        forwarder.forwardTo(new UpstreamTarget(target, false));
    }

    @Override
    public SiteHealth getHealth() {
        return upstream != null ? SiteHealth.UP : SiteHealth.DOWN;
    }

    @Override
    public void destroy() {
        // Deliberately nothing: see the class doc.
    }
}
