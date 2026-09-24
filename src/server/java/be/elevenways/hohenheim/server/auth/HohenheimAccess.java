package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityScope;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.data.RecordSourceGate;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;
import java.util.function.Function;

/**
 * THE per-record access policy funnel. Sites still use a SINGLE capability string
 * ({@link #MANAGE}) covering view, edit and operate together; INSTANCES carry the
 * split vocabulary the Phase 3/5/6 gates need (view/console/power/config/destroy,
 * plus the file, snapshot, backup, image and exec verbs), with {@link #MANAGE} kept as the
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
 * AIDEV-NOTE: this class is THE public funnel and the home of the capability vocabulary;
 * the mechanics live in package-private collaborators it delegates to (HohenheimGrantPolicy
 * for the boot-time declarations, RecordOwners for ownership, OperationGates for the
 * service-side gates, CapabilityScopes for the set-wise walk and its request memo). Callers
 * keep asking HohenheimAccess; a collaborator made public would be a second entry point.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.2.0
 */
public final class HohenheimAccess {

    /** The single v1 capability on a site record. */
    public static final String MANAGE = "manage";

    /** Read a record's own state: DNS record fields, certificate status (never key material). */
    public static final String VIEW = "view";

    /** Author a DNS record inside the delegated type allow-list. */
    public static final String EDIT = "edit";

    /** Mint and hold a DNS record's dyndns update token. */
    public static final String DYNDNS = "dyndns";

    // AIDEV-NOTE: there is deliberately no `request` capability on CertificateModel.
    // One was registered here until 2026-08-13 and NOTHING ever read it: authority to
    // order a certificate is decided by NAME COVERAGE in CertificateAuthority.authorize
    // (every requested name must be covered by a live domain row of a site the caller
    // holds `manage` on), which is a different question from a per-certificate grant --
    // the certificate the grant would sit on does not exist yet when the request is
    // made. Because zenit-auth's RecordAccessPage draws one grant column per REGISTERED
    // capability, the registration alone put a `request` checkbox in front of operators
    // that granted nothing while reporting success. Do not re-add it without a reader.

    /**
     * Attach to the instance's OWN primary process: the read-only console stream, the
     * console command lane and the VM framebuffer. ORDINARY per the plan's sensitivity
     * classes, and deliberately NOT {@link #EXEC}: a console line reaches the workload's
     * stdin, never an arbitrary program as an arbitrary user.
     */
    public static final String CONSOLE = "console";

    /** Start, stop and restart the workload. ORDINARY: it changes runtime state, never content. */
    public static final String POWER = "power";

    /**
     * Author what the instance IS: its record fields, its devices, its schedules and an
     * in-place app update. ELEVATED -- editing what runs is one step from running anything.
     */
    public static final String CONFIG = "config";

    /**
     * Tear the workload down and trash the record. ELEVATED: it is irreversible for the
     * tenant's own data, but it is authority over their OWN instance only, so it stays
     * delegable (an operator may hand a tenant lead the right to retire their own boxes).
     */
    public static final String DESTROY = "destroy";

    /**
     * Run an ARBITRARY command as an arbitrary user inside the workload. ADMIN by the
     * plan's sensitivity classes: it is root-in-container and therefore a host-escape
     * amplifier, so {@link KnownCapability} makes it structurally non-delegable, never
     * owner-implied, and (the rule this wave added) impossible to reach through
     * {@link #MANAGE}'s umbrella. An operator may still grant it deliberately; a tenant
     * holding it can never pass it on.
     */
    public static final String EXEC = "exec";

    /**
     * Open an INTERACTIVE login shell inside the workload -- the tenant verb the product's
     * "your own box" promise is made of, and deliberately NOT {@link #EXEC}.
     *
     * ELEVATED and DELEGABLE: unlike exec it is bounded to a workload that already runs as
     * a NON-ROOT uid (the shell surface refuses every other kind BY NAME), so what it hands
     * out is authority over the tenant's own files and processes rather than
     * root-in-container. That is what makes it something an operator may hand to a tenant
     * lead, where exec never can be.
     *
     * AIDEV-NOTE: deliberately NOT listed in any {@code impliedBy}, {@link #MANAGE}
     * included. Implication is retroactive -- it changes what every ALREADY-STORED grant
     * row means -- so folding a shell into the manage umbrella would silently hand an
     * interactive terminal to every existing manage holder. Same reasoning that keeps
     * the file, snapshot and backup verbs out of that umbrella; an operator grants this one
     * deliberately, on the record, or it is not held.
     */
    public static final String SHELL = "shell";

    /**
     * Read a managed database's CREDENTIALS -- the plaintext {@code db_password} the
     * record stores encrypted. ELEVATED and deliberately separate from {@link #VIEW}: a
     * read-only teammate may see that a database exists, its engine and its status, and
     * still not hold the credential that connects to it as its owner.
     */
    public static final String CREDENTIALS = "credentials";

    /** Take and restore driver-level snapshots of an instance (data-destructive on restore). */
    public static final String SNAPSHOTS = "snapshots";

    /** Export instance backups and restore them to new instances. */
    public static final String BACKUPS = "backups";

    /**
     * Browse, read and download the files inside an instance's own volumes. An ORDINARY
     * tenant capability per the plan's sensitivity classes -- it reads the tenant's own
     * data and nothing else -- and deliberately NOT implied by {@link #FILES_WRITE}: the
     * two are asked for separately on every path in InstanceFiles.
     */
    public static final String FILES_READ = "files.read";

    /**
     * Write, upload, rename, delete and mkdir inside an instance's own volumes. ELEVATED:
     * editing a start script or a jar is editing what runs, which is why it is a separate
     * capability from {@link #FILES_READ} rather than a mode of it.
     */
    public static final String FILES_WRITE = "files.write";

    /**
     * Run an ARBITRARY, non-template image on an instance. Exec-equivalent by the
     * threat model (an attacker-chosen image is attacker-chosen code), so admin/
     * type-level: elevated and deliberately NOT delegable -- a manage holder must not
     * be able to launder it to a third party or mint it into an API-key scope.
     */
    public static final String IMAGE_ANY = "image_any";

    /**
     * Type-level authority to CREATE an instance. Deliberately a PERMISSION and not a
     * record capability: no record exists yet, so there is nothing to hold a capability
     * on. It is an eligibility gate only -- the real bounds on a tenant create are the
     * transactional quota (headroom), the image policy (approved templates only) and
     * {@link be.elevenways.hohenheim.server.instance.InstancePlacement} (which host).
     */
    public static final Permission INSTANCES_CREATE = Permission.of("hohenheim.instances.create");

    /**
     * Type-level authority over EVERY site: {@link #MANAGE} on all of them, WITHOUT
     * {@code hohenheim.admin.access}. It rides the walk's type-level row, which sits behind
     * the gate-denial row, so an explicit denial of {@link ManagePanel#ACCESS} still kills it
     * -- and behind the admin row, so it grants strictly less than the admin permission.
     *
     * AIDEV-NOTE: declared on SiteModel and NOWHERE ELSE, and that is a policy decision the
     * mechanism cannot make. {@code RecordCapabilityRules.typeLevelPermission} is per MODEL,
     * not per capability: holding it confers EVERY capability in that model's vocabulary.
     * Sites have exactly one ({@link #MANAGE}), so the two readings coincide. On
     * InstanceModel they would not -- its vocabulary carries {@link #EXEC} and
     * {@link #IMAGE_ANY}, both deliberately admin-only and non-delegable -- so an
     * instances-wide equivalent needs per-capability narrowing in the framework FIRST. Do not
     * copy this declaration onto another model without it.
     *
     * Registered NON-DELEGABLE (ServerMain.installAuthBaselines), following the
     * {@code auth.grants.manage} precedent: a holder of every-site authority minting peers is
     * exactly the spread containment exists to prevent, and admins bypass containment anyway.
     */
    public static final Permission SITES_MANAGE_ALL = Permission.of("hohenheim.sites.manage_all");

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
     * Whether the context holds {@link #MANAGE} on the instance -- the SAME precedence
     * walk as {@link #canManageSite}, over the instance grant vocabulary.
     */
    public static boolean canManageInstance(@NonNull AccessContext ctx, int instanceId) {
        return ctx.hasCapability(InstanceModel.MODEL_ID, instanceId, MANAGE);
    }

    /** Conduit convenience for HTTP handlers. */
    public static boolean canManageInstance(@NonNull Conduit conduit, int instanceId) {
        return canManageInstance(RecordSourceGate.accessContextOf(conduit), instanceId);
    }

    /**
     * Principal-only variant for WebSocket contexts (no conduit at open time), riding
     * the installed WebSocket authenticator's precedence walk.
     */
    public static boolean canManageInstance(@NonNull Principal principal, int instanceId) {
        return hasInstanceCapability(principal, instanceId, MANAGE);
    }

    /**
     * The principal-only face of {@link #hasInstanceCapability(AccessContext, int, String)},
     * for the WebSocket handlers that have no conduit at open or revalidate time. Same
     * precedence walk, umbrella row included -- a manage holder answers yes to console
     * here exactly as they do through a conduit.
     */
    public static boolean hasInstanceCapability(@NonNull Principal principal, int instanceId,
                                                @NonNull String capability) {
        return Zenit.getWebSocketAuthenticator()
            .hasCapability(principal, InstanceModel.MODEL_ID, instanceId, capability);
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
     */
    public static boolean hasInstanceCapability(@NonNull AccessContext ctx, int instanceId,
                                                @NonNull String capability) {
        return ctx.hasCapability(InstanceModel.MODEL_ID, instanceId, capability);
    }

    /**
     * Whether the context holds {@code capability} on the managed database -- the SAME
     * precedence walk every other tier rides, over the database vocabulary. Per-ROW
     * callers use {@link #reachesRecord}; the fresh walk stays for write gates
     * (see the note on {@link #hasInstanceCapability(AccessContext, int, String)}).
     */
    public static boolean hasDatabaseCapability(@NonNull AccessContext ctx, int databaseId,
                                                @NonNull String capability) {
        return ctx.hasCapability(DatabaseModel.MODEL_ID, databaseId, capability);
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
     * Whether two records of one model answer to the SAME owner (equal {@link #MANAGE} subjects).
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
     * THE owner identity of a record: the subjects holding {@link #MANAGE} on it.
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
     * Hand the creation owner {@link #MANAGE} on a record it just created.
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
     * @throws Violations {@code database_not_permitted}
     * @see OperationGates#requireDatabaseCapability
     */
    public static void requireDatabaseCapability(int databaseId, @NonNull String capability) {
        OperationGates.requireDatabaseCapability(databaseId, capability);
    }

    /** THE uniform managed-database refusal; visibility, absence and denial are one answer. */
    public static @NonNull Violations databaseRefusal() {
        return OperationGates.databaseRefusal();
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
     * Every site id the context holds {@link #MANAGE} on.
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
     * Every site id the principal holds {@link #MANAGE} on, for conduit-less contexts.
     *
     * @throws IllegalStateException on an every-site scope
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull Principal principal) {
        return CapabilityScopes.managedSiteIds(principal);
    }
}
