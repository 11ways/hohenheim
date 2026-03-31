package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.sitetype.SiteTypeInfo;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.Map;

/**
 * Server-side extension of SiteTypeInfo that adds handler lifecycle management.
 * Lives in src/server because it depends on Undertow (via SiteRequestHandler).
 */
public interface SiteTypeHandler extends SiteTypeInfo {

    /**
     * Create a request handler for a site of this type.
     */
    SiteRequestHandler createHandler(Row site, Map<String, Object> settings);

    /**
     * Called when a site's configuration changes.
     * Default: destroy old handler and create a new one.
     */
    default SiteRequestHandler onSiteUpdated(SiteRequestHandler existing, Row site,
                                              Map<String, Object> newSettings) {
        existing.destroy();
        return createHandler(site, newSettings);
    }

    /**
     * Called when a site is disabled or deleted.
     */
    default void onSiteRemoved(SiteRequestHandler handler) {
        handler.destroy();
    }
}
