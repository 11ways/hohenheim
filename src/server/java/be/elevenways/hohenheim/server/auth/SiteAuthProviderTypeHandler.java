package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.auth.SiteAuthProviderType;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

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

    /**
     * Transform a submitted config blob before it is persisted, given the record's existing config
     * (null when creating). Lets a provider hash secrets so plaintext is never stored, and carry
     * forward unchanged secrets when an edit leaves a field blank. Default: store as submitted.
     */
    default Map<String, Object> normalizeConfigForSave(Map<String, Object> submitted,
                                                        @Nullable Map<String, Object> existing) {
        return submitted;
    }
}
