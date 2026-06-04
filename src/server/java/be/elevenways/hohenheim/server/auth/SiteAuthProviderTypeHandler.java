package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.auth.SiteAuthProviderType;

/**
 * Server-side extension of SiteAuthProviderType that builds the actual gate. Lives in src/server
 * because the gate depends on Undertow. Parallel to SiteTypeHandler extends SiteTypeInfo.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public interface SiteAuthProviderTypeHandler extends SiteAuthProviderType {

    /**
     * Build a gate for one configured provider record. MUST be pure and non-blocking: no realm
     * probe, no token fetch, no DB call -- reloadRoutes() rebuilds every gate on every site
     * mutation, so construction must never block on a dead remote endpoint. All network I/O
     * happens lazily inside the gate's evaluate(), dispatched off the I/O thread.
     */
    SiteAuthGate createGate(SiteAuthContext context);
}
