package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.session.SessionStore;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE construction path from a stored provider record to a live {@link SiteAuthGate}.
 *
 * Both consumers -- the site-level provider and an access-rule {@code auth_provider} leaf --
 * go through here, so "which type handler, built how, and what counts as unbuildable" has
 * one answer. Every failure returns a null gate with a reason; the CALLER decides what
 * failing closed looks like on its surface.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class SiteAuthGates {

    /** The prefix of the permission a site demands when nothing names one. */
    public static final String SITE_PERMISSION_PREFIX = "hohenheim.site.";

    private SiteAuthGates() {
    }

    /** @return the demanded permission, else {@code hohenheim.site.<slug>}, else null when the site has no slug */
    public static @Nullable String effectivePermission(@Nullable String demanded, @Nullable String siteSlug) {
        if (demanded != null && !demanded.isBlank()) {
            return demanded;
        }
        return siteSlug != null && !siteSlug.isBlank() ? SITE_PERMISSION_PREFIX + siteSlug : null;
    }

    /**
     * A built gate, or the reason there is none.
     *
     * @param refusal a short, operator-facing reason (it names the provider in the UI)
     * @param detail  the underlying failure text, for the log only
     */
    public record Built(@Nullable SiteAuthGate gate, @Nullable String refusal,
                        @Nullable String detail) {
    }

    /**
     * AIDEV-NOTE: a blank permission demands {@code hohenheim.site.<slug>}, as the Node original did; it used to
     * admit any identity the realm authenticates. An explicit permission is used as is.
     *
     * @param providerRow        the stored provider record, or null when it is gone
     * @param requiredPermission the permission the CALLER demands (a rule leaf may narrow
     *                           beyond the record's own column)
     * @param siteSlug           the gated site's slug, which names the default permission
     */
    public static @NonNull Built build(@Nullable Row providerRow,
                                       @Nullable String requiredPermission,
                                       @Nullable String siteSlug,
                                       @NonNull SessionStore sessionStore,
                                       int siteId,
                                       int providerId) {
        if (providerRow == null) {
            return new Built(null, "missing provider", null);
        }

        String providerType = providerRow.get(SiteAuthProviderModel.PROVIDER_TYPE);
        SiteAuthProviderTypeHandler handler = SiteAuthProviders.getHandler(providerType);
        if (handler == null) {
            return new Built(null, "unknown type", providerType);
        }

        try {
            return new Built(handler.createGate(new SiteAuthContext(providerRow, effectivePermission(requiredPermission, siteSlug),
                sessionStore, siteId, providerType, providerId)), null, null);
        } catch (Exception failure) {
            // createGate must be pure; if it throws anyway, the caller fails closed.
            return new Built(null, "misconfigured", String.valueOf(failure.getMessage()));
        }
    }
}
