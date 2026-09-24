package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityScope;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static be.elevenways.hohenheim.server.auth.HohenheimAccess.MANAGE;

/**
 * The set-wise face of the capability walk (tri-state scopes, their request memo and the scope
 * criteria built from them); reached through {@link HohenheimAccess}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class CapabilityScopes {

    /** Request-scoped memo of the walk's set-wise answers, keyed by model + capability. */
    private static final IdentifierKey<Map<String, RecordCapabilityScope>> CAPABILITY_SCOPES =
        IdentifierKey.of("hohenheim", "capability_scopes");

    private CapabilityScopes() {
    }

    /**
     * @return null for admins, else {@code ID IN (the database ids the context holds
     *         {@code capability} on)}, matching NOTHING when there are none
     */
    static @Nullable Criteria databaseScope(@NonNull AccessContext ctx, @NonNull String capability) {
        return grantScope(ctx, Models.get(DatabaseModel.class), DatabaseModel.MODEL_ID,
            capability, DatabaseModel.ID::in);
    }

    /** Every database id the context holds {@code capability} on (walk-confirmed). */
    static @NonNull Set<Integer> databaseIdsWith(@NonNull AccessContext ctx,
                                                 @NonNull String capability) {
        return grantedRecordIds(ctx, DatabaseModel.MODEL_ID, capability);
    }

    /**
     * THE git-provider visibility policy: which provider rows a principal may SEE and
     * therefore pick. Shared providers are offered to every authenticated principal (the
     * operator's declaration that this installation's credential is for general use);
     * everything else is offered only to the subjects the walk confirms {@code manage}
     * for. Anonymous reaches nothing -- a provider row names a host an operator runs.
     *
     * AIDEV-NOTE: the shared half is deliberately NOT a capability. Modelling "may use"
     * as a grant would demand a grant row per (tenant, provider) pair for a credential
     * the operator already decided is general, and the pickers and the /manage list would
     * then answer to two different questions. One criteria, one answer, one home.
     *
     * @return null for an unconstrained scope, else a criteria that never widens past
     *         shared rows plus the confirmed ids
     */
    static @Nullable Criteria gitProviderScope(@NonNull AccessContext ctx) {
        Model model = Models.get(GitProviderModel.class);
        if (ctx.isAnonymous()) {
            return model.matchNone();
        }
        RecordCapabilityScope scope = capabilityScope(ctx, GitProviderModel.MODEL_ID, MANAGE);
        if (scope.isAll()) {
            return null;
        }
        Criteria shared = GitProviderModel.SHARED.eq(true);
        if (scope.isNone()) {
            return shared;
        }
        Set<Integer> managed = intIds(scope.recordIds());
        if (managed.isEmpty()) {
            return shared;
        }
        return new CompositeCriteria(CompositeOperator.OR, shared,
            GitProviderModel.ID.in(managed));
    }

    /**
     * THE access-list visibility policy, the {@link #gitProviderScope} shape verbatim:
     * shared lists are offered to every authenticated principal, everything else only to
     * the subjects the walk confirms {@code manage} for. Anonymous reaches nothing.
     *
     * @return null for an unconstrained scope, else a criteria that never widens past
     *         shared rows plus the confirmed ids
     */
    static @Nullable Criteria accessListScope(@NonNull AccessContext ctx) {
        Model model = Models.get(AccessListModel.class);
        if (ctx.isAnonymous()) {
            return model.matchNone();
        }
        RecordCapabilityScope scope = capabilityScope(ctx, AccessListModel.MODEL_ID, MANAGE);
        if (scope.isAll()) {
            return null;
        }
        Criteria shared = AccessListModel.SHARED.eq(true);
        if (scope.isNone()) {
            return shared;
        }
        Set<Integer> managed = intIds(scope.recordIds());
        if (managed.isEmpty()) {
            return shared;
        }
        return new CompositeCriteria(CompositeOperator.OR, shared,
            AccessListModel.ID.in(managed));
    }

    /**
     * Whether the context may ATTACH this list (to a site or a protected path): the list
     * is shared, or the walk confirms {@code manage} on it. The scope criteria above and
     * this per-row answer are one policy asked two ways.
     */
    static boolean canUseAccessList(@NonNull AccessContext ctx, @Nullable Object listId) {
        if (!(listId instanceof Integer id)) {
            return false;
        }
        if (reachesRecord(ctx, AccessListModel.MODEL_ID, id, MANAGE)) {
            return true;
        }
        Row list = Models.get(AccessListModel.class).findById(id);
        return list != null && Boolean.TRUE.equals(list.get(AccessListModel.SHARED));
    }

    /**
     * @return null for admins (no extra constraint), else {@code ID IN (the instance ids
     *         the context holds {@code capability} on)}, matching NOTHING when there are none
     */
    static @Nullable Criteria instanceScope(@NonNull AccessContext ctx, @NonNull String capability) {
        return grantScope(ctx, Models.get(InstanceModel.class), InstanceModel.MODEL_ID,
            capability, InstanceModel.ID::in);
    }

    /** Every instance id the context holds {@code capability} on (walk-confirmed). */
    static @NonNull Set<Integer> instanceIdsWith(@NonNull AccessContext ctx,
                                                 @NonNull String capability) {
        return grantedRecordIds(ctx, InstanceModel.MODEL_ID, capability);
    }

    /**
     * THE managed-site scoping shape, shared by every source and resource whose rows hang
     * off a site: an ALL scope is unconstrained, a NONE scope matches NOTHING, and a
     * confirmed set gets the criteria {@code forManagedIds} spells over it.
     *
     * AIDEV-NOTE: one definition on purpose. Three hand-rolled copies (site, domain,
     * certificate) is how one of them ends up missing the anonymous branch or answering
     * {@code ID.in(empty)}, which some backends widen instead of refusing.
     *
     * @param model          the model being scoped, for its {@code matchNone()}
     * @param forManagedIds  builds the criteria from the confirmed managed-site ids
     * @return null for an unconstrained scope, else a criteria
     */
    static @Nullable Criteria managedSiteScope(@NonNull AccessContext ctx,
                                               @NonNull Model model,
                                               @NonNull Function<Set<Integer>, Criteria> forManagedIds) {
        return grantScope(ctx, model, SiteModel.MODEL_ID, MANAGE, forManagedIds);
    }

    /**
     * The generalized shape of {@link #managedSiteScope}: the framework's tri-state answer,
     * translated into a criteria. ALL means no extra constraint, NONE matches nothing, and a
     * confirmed SET gets the criteria {@code forGrantedIds} spells over it.
     *
     * AIDEV-NOTE: there are deliberately no {@code isAdmin} or {@code isAnonymous} branches
     * here anymore, and re-adding either would be a SECOND spelling of the walk's own
     * whole-model rows. The scope already answers ALL for the admin bypass and for
     * {@link HohenheimAccess#SITES_MANAGE_ALL}, and NONE for anonymous, for a model with no
     * declared policy and for an EXPLICIT gate denial -- and that last one is the reason the
     * order matters: gate denial precedes the type-level row, so a denied subject holding
     * manage_all must enumerate nothing. Spelling the prefix by hand here is how the two
     * would drift.
     *
     * @param model the model being SCOPED (its {@code matchNone()}), which is not necessarily
     *        the model the capability is held on -- domains scope by their parent site
     */
    static @Nullable Criteria grantScope(@NonNull AccessContext ctx,
                                         @NonNull Model model,
                                         @NonNull Identifier capabilityModel,
                                         @NonNull String capability,
                                         @NonNull Function<Set<Integer>, Criteria> forGrantedIds) {
        RecordCapabilityScope scope = capabilityScope(ctx, capabilityModel, capability);
        if (scope.isAll()) {
            return null;
        }
        if (scope.isNone()) {
            return model.matchNone();
        }
        return forGrantedIds.apply(intIds(scope.recordIds()));
    }

    /**
     * WHICH records of {@code model} the context holds {@code capability} on, as the
     * framework's tri-state (ALL / NONE / a confirmed SET) -- THE set-wise question every
     * scope criteria, panel probe and nav probe here asks.
     *
     * AIDEV-NOTE: never an id set, and never {@code isAdmin || hasPermission(typeLevel)}.
     * Authority from a whole-model row (the admin bypass, {@link HohenheimAccess#SITES_MANAGE_ALL})
     * covers records that carry no grant at all, so a grant-store enumeration answers EMPTY
     * for it -- which reads as "nothing" and silently empties every list while the by-id
     * checks keep answering yes. The hand-rolled candidates-plus-confirm loop that used to
     * live here could not express ALL at all.
     *
     * Memoized per REQUEST on the conduit (the PermissionResolver WALK_CACHE idiom): panel
     * eligibility, scope criteria and the nav probes all ask per render, and grants written
     * mid-request stay next-request-effective. Conduit-less contexts run the walk fresh.
     *
     * AIDEV-NOTE: the memo is a MAP keyed by model+capability, not one attribute per set. One
     * attribute per set is how the second consumer (dns records) quietly ends up outside the
     * budget the first consumer's test pinned.
     */
    static @NonNull RecordCapabilityScope capabilityScope(@NonNull AccessContext ctx,
                                                          @NonNull Identifier model,
                                                          @NonNull String capability) {
        String key = model + "#" + capability;
        Conduit conduit = ctx.conduit();
        if (conduit == null) {
            return ctx.capabilityScope(model, capability);
        }

        Map<String, RecordCapabilityScope> cache = conduit.getAttribute(CAPABILITY_SCOPES);
        if (cache == null) {
            cache = new HashMap<>();
            try {
                conduit.setAttribute(CAPABILITY_SCOPES, cache);
            } catch (UnsupportedOperationException attributeless) {
                // A conduit without attribute storage just pays the walk each call.
            }
        }

        RecordCapabilityScope cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        RecordCapabilityScope scope = ctx.capabilityScope(model, capability);
        cache.put(key, scope);
        return scope;
    }

    /**
     * THE "does this subject reach anything" question, for panel eligibility and NAV-ONLY
     * probes.
     *
     * AIDEV-NOTE: never {@code grantedRecordIds(...).isEmpty()}. That spelling cannot see an
     * ALL scope, so it answers "reaches nothing" for exactly the subjects who reach
     * everything -- panel hidden, resource invisible, list empty -- while their by-id checks
     * keep passing. It is also the spelling that now THROWS on such a scope.
     */
    static boolean reachesAny(@NonNull AccessContext ctx, @NonNull Identifier model,
                              @NonNull String capability) {
        return !capabilityScope(ctx, model, capability).isNone();
    }

    /**
     * Whether the context holds {@code capability} on ONE record, answered off the REQUEST
     * MEMO rather than by a fresh per-record walk.
     *
     * AIDEV-NOTE: this is the shape a LIST asks, once per rendered row, to decide whether
     * that row gets a pencil or an Edit button. The obvious spelling --
     * {@code ctx.hasCapability(model, id, capability)} -- is a grant-store round trip PER
     * ROW, which is an N+1 the page's own scope criteria already paid for once: the
     * set-wise face runs the SAME precedence rows and confirms every candidate through
     * them, so membership in the scope and the per-record answer agree by construction.
     * TenantDomainDnsScopeTest's query budget is what catches the regression when a new
     * per-row predicate reaches for the un-memoized face.
     *
     * The memo's staleness rule applies: a grant written earlier in THIS request is not
     * seen unless {@link #forgetCapabilityScopes} was called, which is correct for a
     * render and is why creation funnels ({@link RecordOwners#grantCreatorManage}) drop it.
     */
    static boolean reachesRecord(@NonNull AccessContext ctx, @NonNull Identifier model,
                                 @Nullable Integer recordId, @NonNull String capability) {
        if (recordId == null) {
            return false;
        }
        RecordCapabilityScope scope = capabilityScope(ctx, model, capability);
        if (scope.isAll()) {
            return true;
        }
        return !scope.isNone() && intIds(scope.recordIds()).contains(recordId);
    }

    /** Whether the context manages at least one site, an every-site scope included. */
    static boolean managesAnySite(@NonNull AccessContext ctx) {
        return reachesAny(ctx, SiteModel.MODEL_ID, MANAGE);
    }

    /**
     * Every site id the context holds {@link HohenheimAccess#MANAGE} on.
     *
     * @throws IllegalStateException on an every-site scope; see {@link #grantedRecordIds}
     */
    static @NonNull Set<Integer> managedSiteIds(@NonNull AccessContext ctx) {
        return grantedRecordIds(ctx, SiteModel.MODEL_ID, MANAGE);
    }

    /**
     * Every record id of {@code model} the context holds {@code capability} on, for feeding a
     * criteria that must name them.
     *
     * @throws IllegalStateException when the scope is ALL, which enumerates nothing -- the
     *         framework refuses to answer that as a set rather than quietly denying every
     *         record. A caller reaching this on a model where a whole-model row can decide
     *         wants {@link #reachesAny} or {@link #capabilityScope} instead.
     */
    static @NonNull Set<Integer> grantedRecordIds(@NonNull AccessContext ctx,
                                                  @NonNull Identifier model,
                                                  @NonNull String capability) {
        return intIds(capabilityScope(ctx, model, capability).recordIds());
    }

    /**
     * Drop the WHOLE request memo of capability scopes (every model and capability),
     * because THIS request just changed the grants it caches.
     *
     * AIDEV-NOTE: the memo is deliberately "grants written mid-request stay
     * next-request-effective" -- correct for an operator editing somebody else's grants,
     * and WRONG for a creation funnel that plants the creator's own manage grant, because
     * the very next thing that happens is zenit-cms verifying the new row against the
     * caller's scope predicate. Without this the scoped create refuses itself with
     * {@code out_of_scope} and rolls back a perfectly legitimate allocation. Call it from
     * the funnel that planted the grant, never speculatively;
     * {@link RecordOwners#grantCreatorManage} already does.
     */
    static void forgetCapabilityScopes(@NonNull AccessContext ctx) {
        Conduit conduit = ctx.conduit();
        Map<String, RecordCapabilityScope> cache = conduit == null ? null
            : conduit.getAttribute(CAPABILITY_SCOPES);
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * The principal-only face of {@link #capabilityScope(AccessContext, Identifier, String)},
     * for the conduit-less callers. The installed WebSocket authenticator is the sanctioned
     * principal-only path and rides the SAME walk, whole-model rows included -- which is why
     * this replaced a hand-rolled candidates-plus-confirm loop that could only ever answer
     * with a set.
     */
    static @NonNull RecordCapabilityScope capabilityScope(@NonNull Principal principal,
                                                          @NonNull Identifier model,
                                                          @NonNull String capability) {
        return Zenit.getWebSocketAuthenticator().capabilityScope(principal, model, capability);
    }

    /**
     * Every site id the principal holds {@link HohenheimAccess#MANAGE} on, for conduit-less
     * contexts.
     *
     * @throws IllegalStateException on an every-site scope; ask
     *         {@link #capabilityScope(Principal, Identifier, String)} where one is possible
     */
    static @NonNull Set<Integer> managedSiteIds(@NonNull Principal principal) {
        return intIds(capabilityScope(principal, SiteModel.MODEL_ID, MANAGE).recordIds());
    }

    /** The walk keys records by their stringified id; every model scoped here keys on an int. */
    private static @NonNull Set<Integer> intIds(@NonNull Set<String> recordIds) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (String raw : recordIds) {
            try {
                ids.add(Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                // A grant may key on any string; a non-numeric one matches no row here.
            }
        }
        return ids;
    }
}
