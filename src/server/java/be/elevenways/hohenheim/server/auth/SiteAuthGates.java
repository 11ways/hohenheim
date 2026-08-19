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
 * @author Jelle De Loecker &lt;jelle@elevenways.be&gt;
 * @since 0.1.0
 */
public final class SiteAuthGates {

    private SiteAuthGates() {
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
     * @param providerRow        the stored provider record, or null when it is gone
     * @param requiredPermission the permission the CALLER demands (a rule leaf may narrow
     *                           beyond the record's own column)
     */
    public static @NonNull Built build(@Nullable Row providerRow,
                                       @Nullable String requiredPermission,
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
            return new Built(handler.createGate(new SiteAuthContext(providerRow, requiredPermission,
                sessionStore, siteId, providerType, providerId)), null, null);
        } catch (Exception failure) {
            // createGate must be pure; if it throws anyway, the caller fails closed.
            return new Built(null, "misconfigured", String.valueOf(failure.getMessage()));
        }
    }
}
