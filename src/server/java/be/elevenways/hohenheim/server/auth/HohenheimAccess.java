package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityScope;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.data.RecordSourceGate;
import be.elevenways.zenit.server.operation.Authorizer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;
import java.util.function.Function;

/**
 * THE per-record access policy funnel. Sites still use a SINGLE capability string
 * ({@link HohenheimCapabilities#MANAGE}) covering view, edit and operate together; INSTANCES carry the
 * split vocabulary the instance gates need (view/console/power/config/destroy,
 * plus the file, snapshot, backup, image and exec verbs), with {@link HohenheimCapabilities#MANAGE} kept as the
 * ownership marker and as the umbrella that IMPLIES the first five. Adding a verb
 * needs no schema change: grants are plain (subject, model, record, capability) tuples.
 * Per-record decisions ride the framework's fixed precedence walk
 * ({@code RecordCapabilities}) through the rules declared in
 * {@link #declareGrantableModels}: {@code hohenheim.admin.access} is the admin
 * bypass, an EXPLICIT denial of {@code hohenheim.manage.access} (the gate)
 * kills every record grant, and on SITES ONLY {@link #SITES_MANAGE_ALL} is the
 * type-level row.
 *
 * The walk answers in TWO shapes and this class exposes both: by record
 * ({@link #canManageSite} and friends) and set-wise ({@link #capabilityScope},
 * {@link #reachesAny}, {@link #grantScope}). Anything asking "which records" or
 * "any records at all" must take the set-wise face -- an id set cannot express
 * every-record authority, and {@link #grantedRecordIds} now REFUSES to pretend
 * otherwise.
 *
 * AIDEV-NOTE: this class is THE public funnel; the capability names live in HohenheimCapabilities and
 * nowhere else. The mechanics live in package-private collaborators it delegates to (HohenheimGrantPolicy
 * for the boot-time declarations, RecordOwners for ownership, OperationGates for the
 * service-side gates, CapabilityScopes for the set-wise walk and its request memo). Callers
 * keep asking HohenheimAccess; a collaborator made public would be a second entry point.
 *
 * Loaded at boot ({@code @BlastAutoLoad}): it is the declaring home of permissions the grants editor lists
 * ({@code Permissions.declared()}), so they are declared before anybody reads that table.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.2.0
 */
@BlastAutoLoad
public final class HohenheimAccess {

    /**
     * Type-level authority to CREATE an instance. Deliberately a PERMISSION and not a
     * record capability: no record exists yet, so there is nothing to hold a capability
     * on. It is an eligibility gate only -- the real bounds on a tenant create are the
     * transactional quota (headroom), the image policy (approved templates only) and
     * {@link be.elevenways.hohenheim.server.instance.InstancePlacement} (which host).
     */
    public static final Permission INSTANCES_CREATE = Permission.declare("hohenheim.instances.create",
        HohenheimMicrocopy.PERMISSION.of("hohenheim_instances_create"), Permission.Delegation.DELEGABLE);

    /**
     * Type-level authority over EVERY site: {@link HohenheimCapabilities#MANAGE} on all of them, WITHOUT
     * {@code hohenheim.admin.access}. It rides the walk's type-level row, which sits behind
     * the gate-denial row, so an explicit denial of {@link ManagePanel#ACCESS} still kills it
     * -- and behind the admin row, so it grants strictly less than the admin permission.
     *
     * AIDEV-NOTE: declared on SiteModel and NOWHERE ELSE, and that is a policy decision the
     * mechanism cannot make. {@code RecordCapabilityRules.typeLevelPermission} is per MODEL,
     * not per capability: holding it confers EVERY capability in that model's vocabulary.
     * Sites have exactly one ({@link HohenheimCapabilities#MANAGE}), so the two readings coincide. On
     * InstanceModel they would not -- its vocabulary carries {@link HohenheimCapabilities#EXEC} and
     * {@link HohenheimCapabilities#IMAGE_ANY}, both deliberately admin-only and non-delegable -- so an
     * instances-wide equivalent needs per-capability narrowing in the framework FIRST. Do not
     * copy this declaration onto another model without it.
     *
     * Declared DELEGABLE (the owner's call of 2026-08-15, see ServerMain.installAuthBaselines): a
     * permission is a leaf, and holding it includes handing it on.
     */
    public static final Permission SITES_MANAGE_ALL = Permission.declare("hohenheim.sites.manage_all",
        HohenheimMicrocopy.PERMISSION.of("hohenheim_sites_manage_all"), Permission.Delegation.DELEGABLE);

    /** How a packed subject set separates its entries; no subject token can contain it. */
    public static final String SUBJECT_SEPARATOR = "\n";

    private HohenheimAccess() {
    }

    /** Whether the context may create instances at all (admins always may). */
    public static boolean canCreateInstances(@NonNull AccessContext ctx) {
        return isAdmin(ctx) || ctx.hasPermission(INSTANCES_CREATE);
    }

    /**
     * @return true when the context holds the installation-wide admin permission
     */
    public static boolean isAdmin(@NonNull AccessContext ctx) {
        return ctx.hasPermission(HohenheimPanel.ACCESS);
    }

    /**
     * An operation authorizer admitting operators alone, from every surface; anyone else is refused FORBIDDEN.
     *
     * @param diagnostic the refusal's diagnostic, naming the operator act
     */
    public static <S, I> @NonNull Authorizer<S, I> operatorOnly(@NonNull String diagnostic) {
        return (subject, input, access) -> isAdmin(access) ? null
            : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, diagnostic);
    }

    /**
     * Whether the context holds {@link HohenheimCapabilities#MANAGE} on the site, decided by the
     * framework's precedence walk (admin bypass, gate denial, grants) -- never
     * by a grants-only lookup beside it.
     */
    public static boolean canManageSite(@NonNull AccessContext ctx, int siteId) {
        return ctx.hasCapability(SiteModel.MODEL_ID, siteId, HohenheimCapabilities.MANAGE);
    }

    /**
     * Conduit convenience for HTTP handlers.
     */
    public static boolean canManageSite(@NonNull Conduit conduit, int siteId) {
        return canManageSite(RecordSourceGate.accessContextOf(conduit), siteId);
    }

    /**
     * Principal-only variant for WebSocket contexts (no conduit at open time): a
     * detached context rides the SAME precedence walk as the context variant.
     */
    public static boolean canManageSite(@NonNull Principal principal, int siteId) {
        return AccessContext.detached(principal).hasCapability(SiteModel.MODEL_ID, siteId,
            HohenheimCapabilities.MANAGE);
    }

    /**
     * Whether the context holds {@link HohenheimCapabilities#MANAGE} on the instance -- the SAME precedence
     * walk as {@link #canManageSite}, over the instance grant vocabulary.
     */
    public static boolean canManageInstance(@NonNull AccessContext ctx, int instanceId) {
        return hasInstanceCapability(ctx, instanceId, HohenheimCapabilities.MANAGE);
    }

    /** Conduit convenience for HTTP handlers. */
    public static boolean canManageInstance(@NonNull Conduit conduit, int instanceId) {
        return canManageInstance(RecordSourceGate.accessContextOf(conduit), instanceId);
    }

    /**
     * Principal-only variant for WebSocket contexts (no conduit at open time), riding
     * a detached context's precedence walk.
     */
    public static boolean canManageInstance(@NonNull Principal principal, int instanceId) {
        return hasInstanceCapability(principal, instanceId, HohenheimCapabilities.MANAGE);
    }

    /**
     * The principal-only face of {@link #hasInstanceCapability(AccessContext, int, String)},
     * for the WebSocket handlers that have no conduit at open or revalidate time. Same
     * precedence walk, umbrella row included -- a manage holder answers yes to console
     * here exactly as they do through a conduit.
     */
    public static boolean hasInstanceCapability(@NonNull Principal principal, int instanceId,
                                                @NonNull String capability) {
        return AccessContext.detached(principal).hasCapability(InstanceModel.MODEL_ID, instanceId, capability);
    }

    /**
     * Whether the context holds {@code capability} on the instance -- the SAME precedence
     * walk {@link #canManageInstance} rides, over the wider instance vocabulary.
     *
     * AIDEV-NOTE: this is the FRESH walk, kept deliberately for the once-per-request
     * callers (write gates like requireOperationCapability/TenantWrites, page views,
     * socket handshakes). A predicate that runs once per RENDERED ROW must use
     * {@link #reachesRecord} instead -- converting THIS wrapper would put the request
     * memo (and its documented staleness rule) under every write gate.
     *
     * @param instanceId the instance, null (an unset reference) answering false
     */
    public static boolean hasInstanceCapability(@NonNull AccessContext ctx, @Nullable Integer instanceId,
                                                @NonNull String capability) {
        return instanceId != null && ctx.hasCapability(InstanceModel.MODEL_ID, instanceId, capability);
    }

    /**
     * Whether the context holds {@code capability} on the managed database -- the SAME
     * precedence walk every other tier rides, over the database vocabulary. Per-ROW
     * callers use {@link #reachesRecord}; the fresh walk stays for write gates
     * (see the note on {@link #hasInstanceCapability(AccessContext, int, String)}).
     *
     * @param databaseId the database, null (an unset reference) answering false
     */
    public static boolean hasDatabaseCapability(@NonNull AccessContext ctx, @Nullable Integer databaseId,
                                                @NonNull String capability) {
        return databaseId != null && ctx.hasCapability(DatabaseModel.MODEL_ID, databaseId, capability);
    }

    /**
     * Declare every grantable model, its capability vocabulary and its walk rules.
     *
     * @see HohenheimGrantPolicy#declareGrantableModels
     */
    public static void declareGrantableModels() {
        HohenheimGrantPolicy.declareGrantableModels();
    }

    // ---- Record ownership (RecordOwners) ----

    /**
     * Whether two records of one model answer to the SAME owner (equal {@link HohenheimCapabilities#MANAGE} subjects).
     *
     * @return true for the same owner, failing CLOSED to false when grants cannot be read
     * @see RecordOwners#sameOwner(Identifier, Object, Object)
     */
    public static boolean sameOwner(@NonNull Identifier model, @NonNull Object firstId,
                                    @NonNull Object secondId) {
        return RecordOwners.sameOwner(model, firstId, secondId);
    }

    /** Site convenience over {@link #sameOwner(Identifier, Object, Object)}. */
    public static boolean sameOwner(int firstSiteId, int secondSiteId) {
        return RecordOwners.sameOwner(firstSiteId, secondSiteId);
    }

    /**
     * THE owner identity of a record: the subjects holding {@link HohenheimCapabilities#MANAGE} on it.
     *
     * @return the manage-grant subjects, or null when grants are unreadable (callers fail closed)
     * @see RecordOwners#manageSubjectsOf(Identifier, Object)
     */
    public static @Nullable Set<String> manageSubjectsOf(@NonNull Identifier model,
                                                         @NonNull Object recordId) {
        return RecordOwners.manageSubjectsOf(model, recordId);
    }

    /** Site convenience over {@link #manageSubjectsOf(Identifier, Object)}. */
    public static @Nullable Set<String> manageSubjectsOf(int siteId) {
        return RecordOwners.manageSubjectsOf(SiteModel.MODEL_ID, siteId);
    }

    /** THE canonical packing of a subject set; see {@link RecordOwners#packSubjects}. */
    public static @NonNull String packSubjects(@NonNull Set<String> subjects) {
        return RecordOwners.packSubjects(subjects);
    }

    /** The inverse of {@link #packSubjects}; null/"" parses to the empty (operator) set. */
    public static @NonNull Set<String> parseSubjects(@Nullable Object packed) {
        return RecordOwners.parseSubjects(packed);
    }

    /** THE human label of ONE packed subject; see {@link RecordOwners#subjectLabel}. */
    public static @NonNull String subjectLabel(@NonNull String subject) {
        return RecordOwners.subjectLabel(subject);
    }

    /**
     * A whole packed subject set rendered for a reader, in the canonical order.
     *
     * @return the joined labels, empty for the operator-owned (empty) set
     */
    public static @NonNull String labelSubjects(@Nullable Object packed) {
        return RecordOwners.labelSubjects(packed);
    }

    /**
     * Run {@code body} with the creation-owner derivation pinned to {@code subjects}.
     *
     * @throws IllegalStateException when a creation owner is already pinned
     * @see RecordOwners#withCreationOwner
     */
    public static void withCreationOwner(@NonNull Set<String> subjects, @NonNull Runnable body) {
        RecordOwners.withCreationOwner(subjects, body);
    }

    /**
     * THE owner identity a NEW record created by this context will answer to.
     *
     * @see RecordOwners#creationOwnerSubjects
     */
    public static @NonNull Set<String> creationOwnerSubjects(@Nullable AccessContext ctx) {
        return RecordOwners.creationOwnerSubjects(ctx);
    }

    /**
     * Hand the creation owner {@link HohenheimCapabilities#MANAGE} on a record it just created.
     *
     * @see RecordOwners#grantCreatorManage
     */
    public static void grantCreatorManage(@NonNull Identifier model, @NonNull Object recordId,
                                          @Nullable AccessContext ctx) {
        RecordOwners.grantCreatorManage(model, recordId, ctx);
    }

    /**
     * Undo {@link #grantCreatorManage} for a create that is being compensated.
     *
     * @see RecordOwners#revokeCreatorManage
     */
    public static void revokeCreatorManage(@NonNull Identifier model, @NonNull Object recordId,
                                           @Nullable AccessContext ctx) {
        RecordOwners.revokeCreatorManage(model, recordId, ctx);
    }

    // ---- Service-side operation gates (OperationGates) ----

    /**
     * THE operation-funnel gate for a capability-sensitive instance act.
     *
     * @throws Violations {@code instance_not_permitted}
     * @see OperationGates#requireOperationCapability
     */
    public static void requireOperationCapability(int instanceId, @NonNull String capability) {
        OperationGates.requireOperationCapability(instanceId, capability);
    }

    /**
     * THE destroy decision as a render asks it: the dead Delete's reason, or null when it can run.
     *
     * @see OperationGates#destroyUnavailableReason
     */
    public static @Nullable Microcopy destroyUnavailableReason(@NonNull AccessContext ctx,
                                                               int instanceId) {
        return OperationGates.destroyUnavailableReason(ctx, instanceId);
    }

    /**
     * THE destroy gate as a write asks it.
     *
     * @throws Violations {@code instance_not_permitted}
     * @see OperationGates#requireDestroyPermitted
     */
    public static void requireDestroyPermitted(int instanceId) {
        OperationGates.requireDestroyPermitted(instanceId);
    }

    /**
     * THE operator gate of an instance-tier act no delegation reaches.
     *
     * @throws Violations {@code instance_not_permitted}
     * @see OperationGates#requireOperatorOperation
     */
    public static void requireOperatorOperation() {
        OperationGates.requireOperatorOperation();
    }

    /**
     * THE operation-funnel gate for a capability-sensitive managed-database act.
     *
     * @throws Violations {@link HohenheimViolations#databaseNotPermitted}, the one answer for an invisible, absent or denied database
     * @see OperationGates#requireDatabaseCapability
     */
    public static void requireDatabaseCapability(int databaseId, @NonNull String capability) {
        OperationGates.requireDatabaseCapability(databaseId, capability);
    }

    // ---- Set-wise scopes and their request memo (CapabilityScopes) ----

    /**
     * @return null for admins, else the database ids the context holds {@code capability} on
     * @see CapabilityScopes#databaseScope
     */
    public static @Nullable Criteria databaseScope(@NonNull AccessContext ctx,
                                                   @NonNull String capability) {
        return CapabilityScopes.databaseScope(ctx, capability);
    }

    /** Every database id the context holds {@code capability} on (walk-confirmed). */
    @NonNull
    public static Set<Integer> databaseIdsWith(@NonNull AccessContext ctx,
                                               @NonNull String capability) {
        return CapabilityScopes.databaseIdsWith(ctx, capability);
    }

    /** THE git-provider visibility policy; see {@link CapabilityScopes#gitProviderScope}. */
    public static @Nullable Criteria gitProviderScope(@NonNull AccessContext ctx) {
        return CapabilityScopes.gitProviderScope(ctx);
    }

    /** THE access-list visibility policy; see {@link CapabilityScopes#accessListScope}. */
    public static @Nullable Criteria accessListScope(@NonNull AccessContext ctx) {
        return CapabilityScopes.accessListScope(ctx);
    }

    /** Whether the context may ATTACH this list; see {@link CapabilityScopes#canUseAccessList}. */
    public static boolean canUseAccessList(@NonNull AccessContext ctx, @Nullable Object listId) {
        return CapabilityScopes.canUseAccessList(ctx, listId);
    }

    /**
     * @return null for admins, else the instance ids the context holds {@code capability} on
     * @see CapabilityScopes#instanceScope
     */
    public static @Nullable Criteria instanceScope(@NonNull AccessContext ctx,
                                                   @NonNull String capability) {
        return CapabilityScopes.instanceScope(ctx, capability);
    }

    /** Every instance id the context holds {@code capability} on (walk-confirmed). */
    @NonNull
    public static Set<Integer> instanceIdsWith(@NonNull AccessContext ctx,
                                               @NonNull String capability) {
        return CapabilityScopes.instanceIdsWith(ctx, capability);
    }

    /**
     * THE managed-site scoping shape; see {@link CapabilityScopes#managedSiteScope}.
     *
     * @return null for an unconstrained scope, else a criteria
     */
    public static @Nullable Criteria managedSiteScope(@NonNull AccessContext ctx,
                                                      @NonNull Model model,
                                                      @NonNull Function<Set<Integer>, Criteria> forManagedIds) {
        return CapabilityScopes.managedSiteScope(ctx, model, forManagedIds);
    }

    /**
     * The walk's tri-state answer translated into a criteria; see {@link CapabilityScopes#grantScope}.
     *
     * @param model the model being SCOPED, not necessarily the one the capability is held on
     */
    public static @Nullable Criteria grantScope(@NonNull AccessContext ctx,
                                                @NonNull Model model,
                                                @NonNull Identifier capabilityModel,
                                                @NonNull String capability,
                                                @NonNull Function<Set<Integer>, Criteria> forGrantedIds) {
        return CapabilityScopes.grantScope(ctx, model, capabilityModel, capability, forGrantedIds);
    }

    /**
     * WHICH records of {@code model} the context holds {@code capability} on, memoized per request.
     *
     * @see CapabilityScopes#capabilityScope(AccessContext, Identifier, String)
     */
    @NonNull
    public static RecordCapabilityScope capabilityScope(@NonNull AccessContext ctx,
                                                        @NonNull Identifier model,
                                                        @NonNull String capability) {
        return CapabilityScopes.capabilityScope(ctx, model, capability);
    }

    /** THE "does this subject reach anything" question; see {@link CapabilityScopes#reachesAny}. */
    public static boolean reachesAny(@NonNull AccessContext ctx, @NonNull Identifier model,
                                     @NonNull String capability) {
        return CapabilityScopes.reachesAny(ctx, model, capability);
    }

    /**
     * Whether the context holds {@code capability} on ONE record, answered off the request memo.
     *
     * @see CapabilityScopes#reachesRecord
     */
    public static boolean reachesRecord(@NonNull AccessContext ctx, @NonNull Identifier model,
                                        @Nullable Integer recordId, @NonNull String capability) {
        return CapabilityScopes.reachesRecord(ctx, model, recordId, capability);
    }

    /** Whether the context manages at least one site, an every-site scope included. */
    public static boolean managesAnySite(@NonNull AccessContext ctx) {
        return CapabilityScopes.managesAnySite(ctx);
    }

    /**
     * Every site id the context holds {@link HohenheimCapabilities#MANAGE} on.
     *
     * @throws IllegalStateException on an every-site scope; see {@link #grantedRecordIds}
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull AccessContext ctx) {
        return CapabilityScopes.managedSiteIds(ctx);
    }

    /**
     * Every record id of {@code model} the context holds {@code capability} on.
     *
     * @throws IllegalStateException when the scope is ALL; see {@link CapabilityScopes#grantedRecordIds}
     */
    @NonNull
    public static Set<Integer> grantedRecordIds(@NonNull AccessContext ctx,
                                                @NonNull Identifier model,
                                                @NonNull String capability) {
        return CapabilityScopes.grantedRecordIds(ctx, model, capability);
    }

    /**
     * Drop the WHOLE request memo of capability scopes, because this request changed the grants.
     *
     * @see CapabilityScopes#forgetCapabilityScopes
     */
    public static void forgetCapabilityScopes(@NonNull AccessContext ctx) {
        CapabilityScopes.forgetCapabilityScopes(ctx);
    }

    /** The principal-only face of {@link #capabilityScope(AccessContext, Identifier, String)}. */
    @NonNull
    public static RecordCapabilityScope capabilityScope(@NonNull Principal principal,
                                                        @NonNull Identifier model,
                                                        @NonNull String capability) {
        return CapabilityScopes.capabilityScope(principal, model, capability);
    }

    /**
     * Every site id the principal holds {@link HohenheimCapabilities#MANAGE} on, for conduit-less contexts.
     *
     * @throws IllegalStateException on an every-site scope
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull Principal principal) {
        return CapabilityScopes.managedSiteIds(principal);
    }
}
