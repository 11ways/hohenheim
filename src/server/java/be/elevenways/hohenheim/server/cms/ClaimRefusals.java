package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.Function;

/**
 * THE refusal text for a route another site holds: the holding site is named only to a
 * reader who may manage it, everyone else gets one neutral sentence.
 *
 * AIDEV-NOTE: the detailed sentences ("already claimed by site X", "overlaps *.example.com,
 * routed by site Y") are actionable for an operator and an existence-plus-identity ORACLE
 * for anyone else: a tenant probing hostnames through the /manage domain form could
 * enumerate every hostname, every wildcard pattern and every site name on the box. The
 * neutral sentence carries NO fact about the holder and is byte-identical whether the route
 * is held by a foreign site or covered by a foreign wildcard -- the only way to make
 * existence unobservable. Every refusal that names a foreign site routes through here so
 * the two cases cannot drift; the quarantine refusal (route_quarantined) never named an
 * owner and stays where it is.
 */
final class ClaimRefusals {

    /** The neutral hostname refusal, anchored on the hostname a tenant typed. */
    static final String HOSTNAME_UNAVAILABLE = "hostname_unavailable";

    /** The neutral site-enable refusal, naming only the tenant's OWN hostname. */
    static final String ENABLE_HOSTNAME_UNAVAILABLE = "enable_hostname_unavailable";

    private ClaimRefusals() {
    }

    /**
     * The refusal for a route held by {@code holder}.
     *
     * @param holderSiteId the holding site, null when it vanished mid-decision
     * @param holder       the holding site's row, null when it vanished
     * @param detailed     builds the sentence naming the holder, given its display name
     * @param neutral      the sentence for a reader who may not learn who the holder is;
     *                     it may name the writer's OWN hostname and nothing of the holder's
     */
    static @NonNull Microcopy heldBy(@Nullable Integer holderSiteId, @Nullable Row holder,
                                     @NonNull Function<String, Microcopy> detailed,
                                     @NonNull Microcopy neutral) {
        if (!mayReadHolder(holderSiteId)) {
            return neutral;
        }
        return detailed.apply(holder != null
            ? String.valueOf(holder.get(SiteModel.NAME)) : "#" + holderSiteId);
    }

    /**
     * Whether the writer in flight may learn WHICH site holds a route: system work and
     * administrators always, a tenant only for a site it manages itself.
     */
    private static boolean mayReadHolder(@Nullable Integer holderSiteId) {
        AccessContext ctx = TenantWrites.acting();
        if (ctx == null || HohenheimAccess.isAdmin(ctx)) {
            return true;
        }
        return holderSiteId != null && HohenheimAccess.canManageSite(ctx, holderSiteId);
    }
}
