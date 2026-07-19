package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.auth.SiteAuthProviderType;
import be.elevenways.protoblast.common.annotation.BlastDiscoverable;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Server-side extension of SiteAuthProviderType that builds the actual gate. Lives in src/server
 * because the gate depends on Undertow. Parallel to SiteTypeHandler extends SiteTypeInfo.
 * Implementations are discovered at compile time and self-register via {@code typeId()}.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
@BlastDiscoverable(registrar = "be.elevenways.hohenheim.server.auth.SiteAuthProviders#register")
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
     * (null when creating). This is the provider's normalization boundary for its storage shape.
     */
    default Map<String, Object> normalizeConfigForSave(Map<String, Object> submitted,
                                                        @Nullable Map<String, Object> existing) {
        return submitted;
    }
}
