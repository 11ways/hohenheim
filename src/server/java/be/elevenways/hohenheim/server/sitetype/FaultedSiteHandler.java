package be.elevenways.hohenheim.server.sitetype;

import io.undertow.server.HttpServerExchange;

/**
 * Handler for a site whose configuration failed fail-fast validation. Every request gets a 503
 * naming the problem, instead of the site half-starting and failing somewhere deeper.
 */
public final class FaultedSiteHandler implements SiteRequestHandler {

    private final int siteId;
    private final String reason;

    public FaultedSiteHandler(int siteId, String reason) {
        this.siteId = siteId;
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
        exchange.setStatusCode(503);
        exchange.getResponseSender().send("Site misconfigured: " + reason);
    }

    @Override
    public int getSiteId() {
        return siteId;
    }

    @Override
    public SiteHealth getHealth() {
        return SiteHealth.DOWN;
    }
}
