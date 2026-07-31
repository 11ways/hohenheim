package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.server.GrantableModel;
import be.elevenways.zenit.auth.server.RecordGrantCapabilityChecker;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityRules;
import be.elevenways.zenit.common.security.WebSocketAuthenticator;
import be.elevenways.zenit.server.data.RecordSourceGate;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The ONE per-site access policy funnel: v1 uses a SINGLE capability string
 * ({@link #MANAGE}) on the {@code hohenheim:site} model covering view, edit
 * and operate together -- finer verbs can be added later without any schema
 * change, since grants are plain (subject, model, record, capability) tuples.
 * Per-record decisions ride the framework's fixed precedence walk
 * ({@code RecordCapabilities}) through the rules declared in
 * {@link #declareGrantableModels}: {@code hohenheim.admin.access} is the admin
 * bypass and an EXPLICIT denial of {@code hohenheim.manage.access} (the gate)
 * kills every record grant.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.2.0
 */
public final class HohenheimAccess {

    /** The single v1 capability on a site record. */
    public static final String MANAGE = "manage";

    private HohenheimAccess() {
    }

    /**
     * The boot-time half of this policy. Sites are the ONE model here that holds
     * record grants, and zenit-auth refuses a grant on an undeclared model;
     * declaring it is also what keeps the grant-cleanup hooks off every other
     * model's deletes. The capability VOCABULARY (manage is delegable, so a
     * holder may mint the {@code cap:hohenheim:site#manage} API-key scope) and
     * the walk's composition RULES land here too, so the enforcement path and
     * the delegation path can never see different policies.
     */
    public static void declareGrantableModels() {
        // AIDEV-NOTE: liveWhen is NOT optional here. Sites soft-delete by hand -- the
        // resource stamps deleted_at through save() without SoftDeleteBehaviour attached --
        // so a trashed site's row is still physically present. Without this predicate the
        // framework's presence-only default counted it as alive: its grants survived the
        // orphan sweep and came straight back the moment the site was restored, handing an
        // operator authority the delete had already withdrawn. The SAME predicate also
        // stops a new grant being planted on a trashed site.
        RecordGrants.declareGrantable(GrantableModel.of(SiteModel.MODEL_ID)
            .liveWhen(row -> row.get(SiteModel.DELETED_AT) == null));
        KnownCapabilities.register(SiteModel.MODEL_ID,
            KnownCapability.of(MANAGE)
                .label(Microcopy.of("manage").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(SiteModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));
    }

    /**
     * @return true when the context holds the installation-wide admin permission
     */
    public static boolean isAdmin(@NonNull AccessContext ctx) {
        return ctx.hasPermission(HohenheimPanel.ACCESS);
    }

    /**
     * Whether the context holds {@link #MANAGE} on the site, decided by the
     * framework's precedence walk (admin bypass, gate denial, grants) -- never
     * by a grants-only lookup beside it.
     */
    public static boolean canManageSite(@NonNull AccessContext ctx, int siteId) {
        return ctx.hasCapability(SiteModel.MODEL_ID, siteId, MANAGE);
    }

    /**
     * Conduit convenience for HTTP handlers.
     */
    public static boolean canManageSite(@NonNull Conduit conduit, int siteId) {
        return canManageSite(RecordSourceGate.accessContextOf(conduit), siteId);
    }

    /**
     * Principal-only variant for WebSocket contexts (no conduit at open time):
     * the installed WebSocket authenticator is the sanctioned principal-only
     * path, and it rides the SAME precedence walk as the context variant.
     */
    public static boolean canManageSite(@NonNull Principal principal, int siteId) {
        return Zenit.getWebSocketAuthenticator()
            .hasCapability(principal, SiteModel.MODEL_ID, siteId, MANAGE);
    }

    /**
     * Every site id the context holds {@link #MANAGE} on, for feeding scope
     * criteria (the walk decides per record and offers no enumeration, so
     * candidates come from the grant store and each one is CONFIRMED through
     * the walk -- which re-applies admin bypass and gate denial).
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull AccessContext ctx) {
        return confirmedSiteIds(ctx.principal(),
            id -> ctx.hasCapability(SiteModel.MODEL_ID, id, MANAGE));
    }

    /**
     * Principal-only enumeration for conduit-less contexts, confirmed through
     * the same walk via the installed WebSocket authenticator.
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull Principal principal) {
        WebSocketAuthenticator authenticator = Zenit.getWebSocketAuthenticator();
        return confirmedSiteIds(principal,
            id -> authenticator.hasCapability(principal, SiteModel.MODEL_ID, id, MANAGE));
    }

    /**
     * @return the grant-derived candidate ids the walk confirms (unparseable ids skipped)
     */
    @NonNull
    private static Set<Integer> confirmedSiteIds(@NonNull Principal principal,
                                                 @NonNull Predicate<String> confirmedByWalk) {
        if (principal.isAnonymous()) {
            return Set.of();
        }
        Set<Integer> ids = new HashSet<>();
        for (String raw : RecordGrants.recordIds(principal, SiteModel.MODEL_ID, MANAGE)) {
            if (!confirmedByWalk.test(raw)) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                // Grants store record ids as strings; non-numeric ones cannot be sites.
            }
        }
        return ids;
    }
}
