package be.elevenways.hohenheim.server.upstream;

import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.upstream.UpstreamKindInfo;
import be.elevenways.protoblast.common.annotation.BlastDiscoverable;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Server-side extension of UpstreamKindInfo that adds handler lifecycle management.
 * Lives in src/server because it depends on Undertow (via SiteRequestHandler).
 * Implementations are discovered at compile time and register themselves via their
 * {@code typeId()} -- adding an upstream kind ships one class, registered nowhere
 * manually.
 */
@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers#register")
public interface UpstreamKindHandler extends UpstreamKindInfo {

    /** Create a request handler for a site with this upstream. */
    SiteRequestHandler createHandler(Row site, Map<String, Object> settings);

    /**
     * One short line saying where this site's traffic actually goes (the list cell
     * under the kind badge: "10.0.0.4:8080", "/srv/www", a redirect target).
     *
     * @return the summary, or null when the kind has nothing terser than its label
     */
    default @Nullable String upstreamSummary(
            Map<String, Object> settings) {
        return null;
    }

    /**
     * Called when a site's configuration changes.
     * Default: destroy old handler and create a new one.
     */
    default SiteRequestHandler onSiteUpdated(SiteRequestHandler existing, Row site,
                                              Map<String, Object> newSettings) {
        existing.destroy();
        return createHandler(site, newSettings);
    }

    /** Called when a site is disabled or deleted. */
    default void onSiteRemoved(SiteRequestHandler handler) {
        handler.destroy();
    }
}
