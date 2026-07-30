package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.server.data.RecordSourceGate;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * The ONE per-site access policy funnel: v1 uses a SINGLE capability string
 * ({@link #MANAGE}) on the {@code hohenheim:site} model covering view, edit
 * and operate together -- finer verbs can be added later without any schema
 * change, since grants are plain (subject, model, record, capability) tuples.
 * The {@code hohenheim.admin.access} permission bypasses everything.
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
     * The boot-time half of this policy: sites are the ONE model here that holds record
     * grants, and zenit-auth refuses a grant on an undeclared model. Declaring it is
     * also what keeps the grant-cleanup hooks off every other model's deletes.
     */
    public static void declareGrantableModels() {
        RecordGrants.declareGrantable(SiteModel.MODEL_ID);
    }

    /**
     * @return true when the context holds the installation-wide admin permission
     */
    public static boolean isAdmin(@NonNull AccessContext ctx) {
        return ctx.hasPermission(HohenheimPanel.ACCESS);
    }

    /**
     * @return true when the context is admin or holds {@link #MANAGE} on the site
     */
    public static boolean canManageSite(@NonNull AccessContext ctx, int siteId) {
        if (isAdmin(ctx)) {
            return true;
        }
        if (ctx.isAnonymous()) {
            return false;
        }
        if (isManageAccessDenied(ctx.principal())) {
            return false;
        }
        return RecordGrants.hasCapability(ctx.principal(), SiteModel.MODEL_ID, siteId, MANAGE);
    }

    /**
     * Conduit convenience for HTTP handlers.
     */
    public static boolean canManageSite(@NonNull Conduit conduit, int siteId) {
        return canManageSite(RecordSourceGate.accessContextOf(conduit), siteId);
    }

    /**
     * Principal-only variant for WebSocket contexts (no conduit at open time);
     * the admin check rides the installed WebSocket authenticator, the
     * sanctioned principal-only permission path.
     */
    public static boolean canManageSite(@NonNull Principal principal, int siteId) {
        if (principal.isAnonymous()) {
            return false;
        }
        if (Zenit.getWebSocketAuthenticator().hasPermission(principal, HohenheimPanel.ACCESS)) {
            return true;
        }
        if (isManageAccessDenied(principal)) {
            return false;
        }
        return RecordGrants.hasCapability(principal, SiteModel.MODEL_ID, siteId, MANAGE);
    }

    /**
     * @return every site id the principal holds {@link #MANAGE} on (unparseable ids skipped)
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull Principal principal) {
        if (isManageAccessDenied(principal)) {
            return Set.of();
        }
        Set<Integer> ids = new HashSet<>();
        for (String raw : RecordGrants.recordIds(principal, SiteModel.MODEL_ID, MANAGE)) {
            try {
                ids.add(Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                // Grants store record ids as strings; non-numeric ones cannot be sites.
            }
        }
        return ids;
    }

    private static boolean isManageAccessDenied(@NonNull Principal principal) {
        return Boolean.FALSE.equals(ZenitAuth.permissionResolver()
            .decide(principal, ManagePanel.ACCESS.value()));
    }
}
