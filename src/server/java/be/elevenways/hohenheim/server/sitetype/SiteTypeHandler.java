package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.sitetype.SiteTypeInfo;
import be.elevenways.protoblast.common.annotation.BlastDiscoverable;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.util.Map;

/**
 * Server-side extension of SiteTypeInfo that adds handler lifecycle management.
 * Lives in src/server because it depends on Undertow (via SiteRequestHandler).
 * Implementations are discovered at compile time and register themselves via
 * their {@code typeId()} -- adding a site type ships one class, registered
 * nowhere manually.
 */
@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.sitetype.SiteTypes#register")
public interface SiteTypeHandler extends SiteTypeInfo {

    /**
     * Create a request handler for a site of this type.
     */
    SiteRequestHandler createHandler(Row site, Map<String, Object> settings);

    /**
     * Whether sites of this type spawn OS processes through the managed-process
     * pipeline (workload identity claims, reserved control variables). Docker and
     * proxy-only types stay false: their environment never carries generated
     * control variables.
     */
    default boolean managedProcessEnvironment() {
        return false;
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

    /**
     * Called when a site is disabled or deleted.
     */
    default void onSiteRemoved(SiteRequestHandler handler) {
        handler.destroy();
    }
}
