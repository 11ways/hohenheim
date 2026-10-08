package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import io.undertow.server.HttpServerExchange;

/**
 * Handler for a site whose configuration failed fail-fast validation. Every request gets a 503
 * naming the problem, instead of the site half-starting and failing somewhere deeper.
 *
 * AIDEV-NOTE: the reason is copy, not a String: the same words reach the visitor's 503 (in the installation's
 * default content locale, there being no reader to ask) and, through the routing problem, the app's health band.
 */
public final class FaultedSiteHandler implements SiteRequestHandler {

    private final int siteId;
    private final Microcopy reason;

    public FaultedSiteHandler(int siteId, Microcopy reason) {
        this.siteId = siteId;
        this.reason = reason;
    }

    public Microcopy reason() {
        return reason;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
        exchange.setStatusCode(503);
        exchange.getResponseSender().send(HohenheimViolations.textOf(
            Microcopy.of("site_misconfigured").withFilter("scope", "site_fault").withArg("reason", reason)));
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
