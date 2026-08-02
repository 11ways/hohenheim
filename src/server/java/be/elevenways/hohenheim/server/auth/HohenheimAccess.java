package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.server.GrantableModel;
import be.elevenways.zenit.auth.server.RecordGrantCapabilityChecker;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityRules;
import be.elevenways.zenit.common.security.WebSocketAuthenticator;
import be.elevenways.zenit.server.data.RecordSourceGate;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
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
     * Whether two sites answer to the SAME owner, which is what separates a deliberate
     * configuration from a cross-tenant seizure.
     *
     * AIDEV-NOTE: ownership is the site's set of {@link #MANAGE} grant SUBJECTS, not its
     * site id. Two sites an operator alone controls hold no manage grants at all, so they
     * compare equal and an admin may deliberately point a wildcard at one site and carve
     * one host out to another (a shipped, dispatch-tested capability -- exact beats
     * wildcard, and two upstreams need two sites). The moment either side is TENANT code,
     * the subject sets differ and the same shape becomes a takeover. Equality, not
     * overlap: {A} versus {A, B} would let B seize what A was serving. Mirrors
     * WorkloadIdentity.isTenantManaged, which is the same tenancy predicate one seam over.
     *
     * @return true when both sites carry the same manage-grant subjects (both empty
     *         included), failing CLOSED to "different owners" when grants cannot be read
     */
    public static boolean sameOwner(int firstSiteId, int secondSiteId) {
        if (firstSiteId == secondSiteId) {
            return true;
        }
        Set<String> first = manageSubjectsOf(firstSiteId);
        Set<String> second = manageSubjectsOf(secondSiteId);
        return first != null && second != null && first.equals(second);
    }

    /** @return the subjects holding manage on the site, or null when grants are unreadable */
    private static @Nullable Set<String> manageSubjectsOf(int siteId) {
        Set<String> subjects = new HashSet<>();
        try {
            for (Row grant : RecordGrants.listForRecord(SiteModel.MODEL_ID, siteId)) {
                if (MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                        && Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))) {
                    subjects.add(grant.get(RecordGrantModel.SUBJECT_TYPE)
                        + ":" + grant.get(RecordGrantModel.SUBJECT_ID));
                }
            }
        } catch (IllegalStateException notInstalled) {
            // ZenitAuth.init never ran (tools, minimal tests): no tenants can exist, so
            // every site is operator-owned and the sets are legitimately equal.
            return subjects;
        } catch (RuntimeException unreadable) {
            return null;
        }
        return subjects;
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
     * THE managed-site scoping shape, shared by every source and resource whose rows hang
     * off a site: admins are unconstrained, a principal with no managed sites matches
     * NOTHING, and everyone else gets the criteria {@code forManagedIds} spells over the
     * confirmed id set.
     *
     * AIDEV-NOTE: one definition on purpose. Three hand-rolled copies (site, domain,
     * certificate) is how one of them ends up missing the anonymous branch or answering
     * {@code ID.in(empty)}, which some backends widen instead of refusing.
     *
     * @param model          the model being scoped, for its {@code matchNone()}
     * @param forManagedIds  builds the criteria from the confirmed managed-site ids
     * @return null for admins (no extra constraint), else a criteria
     */
    public static @Nullable Criteria managedSiteScope(@NonNull AccessContext ctx,
                                                      @NonNull Model model,
                                                      @NonNull Function<Set<Integer>, Criteria> forManagedIds) {
        if (isAdmin(ctx)) {
            return null;
        }
        if (ctx.isAnonymous()) {
            return model.matchNone();
        }
        Set<Integer> ids = managedSiteIds(ctx);
        return ids.isEmpty() ? model.matchNone() : forManagedIds.apply(ids);
    }

    /** Request-scoped memo of the confirmed managed-site set (one principal per conduit). */
    private static final IdentifierKey<Set<Integer>> MANAGED_SITE_IDS =
        IdentifierKey.of("hohenheim", "managed_site_ids");

    /**
     * Every site id the context holds {@link #MANAGE} on, for feeding scope
     * criteria (the walk decides per record and offers no enumeration, so
     * candidates come from the grant store and each one is CONFIRMED through
     * the walk -- which re-applies admin bypass and gate denial).
     *
     * Memoized per REQUEST on the conduit (the PermissionResolver WALK_CACHE
     * idiom): panel eligibility, scope criteria and the nav probes all ask per
     * render, and grants written mid-request stay next-request-effective.
     * Conduit-less contexts run the enumeration fresh.
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull AccessContext ctx) {
        Conduit conduit = ctx.conduit();
        if (conduit == null) {
            return enumerateManagedSiteIds(ctx);
        }

        Set<Integer> cached = conduit.getAttribute(MANAGED_SITE_IDS);
        if (cached != null) {
            return cached;
        }

        Set<Integer> ids = enumerateManagedSiteIds(ctx);
        try {
            conduit.setAttribute(MANAGED_SITE_IDS, ids);
        } catch (UnsupportedOperationException attributeless) {
            // A conduit without attribute storage just pays the walk each call.
        }
        return ids;
    }

    private static @NonNull Set<Integer> enumerateManagedSiteIds(@NonNull AccessContext ctx) {
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
