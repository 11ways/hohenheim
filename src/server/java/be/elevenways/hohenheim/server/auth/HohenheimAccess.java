package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.server.GrantableModel;
import be.elevenways.zenit.auth.server.RecordGrantCapabilityChecker;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.common.security.Permission;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.RecordCapabilityRules;
import be.elevenways.zenit.common.security.WebSocketAuthenticator;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.data.RecordSourceGate;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

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
 * bypass and an EXPLICIT denial of {@code hohenheim.manage.access} (the gate)
 * kills every record grant.
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

    /** Order or renew a certificate for names the holder already answers for. */
    public static final String REQUEST = "request";

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

    private HohenheimAccess() {
    }

    /** Whether the context may create instances at all (admins always may). */
    public static boolean canCreateInstances(@NonNull AccessContext ctx) {
        return isAdmin(ctx) || ctx.hasPermission(INSTANCES_CREATE);
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

        // AIDEV-NOTE: DnsZoneModel, DnsPeerModel and DnsZonePeerModel declare NO vocabulary
        // and are NOT grantable, PERMANENTLY and by decision (docs/instance-tier-plan.md,
        // "Phase 2 parallel gate", DECIDED 2026-08-02). A zone row is the DNSSEC/TSIG trust
        // root (dnssec_private_key, tsig_secret, api_key) and every remaining field is SOA
        // policy whose blast radius is the whole zone going dark, so there is no per-field
        // split leaving a tenant a safe subset; creating a zone also ASSERTS a delegation
        // from the parent that hohenheim cannot verify. A tenant never sees a zone row, only
        // names inside one. Do not "helpfully" add one here: the tenant-facing DNS surface is
        // ManageDnsRecordResource, scoped by hostname authority and per-record grants.
        // AIDEV-NOTE: asOwnerImplied() on these two is DECLARED but INERT today -- the walk's
        // owner row only runs when the model's rules name an ownerField, and dns_records has
        // no owning-principal column. It is written down anyway because the decision is that
        // ownership WOULD imply them; the day a column lands, ownedBy() is the only edit.
        // CertificateModel's owner row is live (requested_by_user_id).
        RecordGrants.declareGrantable(GrantableModel.of(DnsRecordModel.MODEL_ID));
        KnownCapabilities.register(DnsRecordModel.MODEL_ID,
            KnownCapability.of(VIEW)
                .label(Microcopy.of("view").withFilter("scope", "capability"))
                .asDelegable()
                .asOwnerImplied(),
            KnownCapability.of(EDIT)
                .label(Microcopy.of("edit").withFilter("scope", "capability"))
                .elevated()
                .asDelegable()
                .asOwnerImplied(),
            // NOT delegable: the minted token is a bearer credential that SURVIVES grant
            // revocation, so re-delegation would launder a permanent capability out of a
            // revocable one. NS/CAA/MX/DS/DNSKEY authoring, managed_by mutation and zone_id
            // reassignment are deliberately not capabilities AT ALL -- each is a
            // zone-compromise primitive, refused in the write pipeline (TenantWrites) for
            // every writer rather than offered as something an operator could grant.
            KnownCapability.of(DYNDNS)
                .label(Microcopy.of("dyndns").withFilter("scope", "capability"))
                .elevated());
        RecordGrantCapabilityChecker.declareRules(DnsRecordModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        // Instances: registering the vocabulary in the SAME commit as the model is
        // load-bearing -- without one, sameOwner on instances compares two EMPTY subject
        // sets and answers "same owner" for every pair: a tenancy check that cannot fail.
        //
        // AIDEV-NOTE: the UMBRELLA DECISION (2026-08-08, Phase 3/5/6 gate work). "manage"
        // is KEPT and stays THE ownership marker (manageSubjectsOf/sameOwner, the quota
        // bucket, the released-claim ledger, project adoption all read it), and the narrow
        // verbs are declared as capabilities manage IMPLIES -- the framework's new
        // KnownCapability.impliedBy row in the precedence walk. The alternative, replacing
        // manage with a set of narrow rows, was rejected: ownership identity would have had
        // to move to a second spelling, and the local dev database already holds applied
        // grant rows that a rewrite would have to migrate.
        //
        // Because implication is exactly the set of verbs that rode manage BEFORE this
        // change, no grant row's effective authority moves and there is therefore NO
        // migration: an existing manage holder keeps precisely view/console/power/config/
        // destroy and, as before, does NOT get files.*/snapshots/backups/image_any/exec.
        // Widening manage to imply those would be a silent privilege grant to every
        // already-stored row, which is why the umbrella deliberately stops where it does.
        //
        // exec cannot be listed as an implier at all: it is ADMIN, and KnownCapability
        // refuses ADMIN + impliedBy structurally. "manage does not imply exec" is thus an
        // invariant of the mechanism, not a line someone could edit here by accident.
        RecordGrants.declareGrantable(GrantableModel.of(InstanceModel.MODEL_ID)
            .liveWhen(row -> row.get(InstanceModel.DELETED_AT) == null));
        KnownCapabilities.register(InstanceModel.MODEL_ID,
            KnownCapability.of(MANAGE)
                .label(Microcopy.of("manage").withFilter("scope", "capability"))
                .elevated()
                .asDelegable(),
            // Seeing the record is implied by every verb that operates on it: an operator
            // handing out "console" must not have to remember to hand out "view" too, or
            // the delegate gets a 404 on the page carrying the console.
            KnownCapability.of(VIEW)
                .label(Microcopy.of("view").withFilter("scope", "capability"))
                .asDelegable()
                .impliedBy(MANAGE, CONSOLE, POWER, CONFIG, DESTROY),
            KnownCapability.of(CONSOLE)
                .label(Microcopy.of("console").withFilter("scope", "capability"))
                .asDelegable()
                .impliedBy(MANAGE),
            KnownCapability.of(POWER)
                .label(Microcopy.of("power").withFilter("scope", "capability"))
                .asDelegable()
                .impliedBy(MANAGE),
            KnownCapability.of(CONFIG)
                .label(Microcopy.of("config").withFilter("scope", "capability"))
                .elevated()
                .asDelegable()
                .impliedBy(MANAGE),
            KnownCapability.of(DESTROY)
                .label(Microcopy.of("destroy").withFilter("scope", "capability"))
                .elevated()
                .asDelegable()
                .impliedBy(MANAGE),
            // ADMIN, so the record enforces non-delegable AND not-owner-implied AND
            // not-implied-by-anything. An operator may still plant it (admins bypass
            // GrantAdministration's containment); the holder can never pass it on, mint it
            // into an API-key scope, or reach it by holding manage.
            KnownCapability.of(EXEC)
                .label(Microcopy.of("exec").withFilter("scope", "capability"))
                .admin(),
            // Phase 4: the snapshot/backup actions now exist (InstanceSnapshots /
            // InstanceBackups behind the admin resources), so their capabilities
            // register per the plan's no-unwired rule. Elevated -- a snapshot
            // restore destroys data and a backup export carries secret variables.
            KnownCapability.of(SNAPSHOTS)
                .label(Microcopy.of("snapshots").withFilter("scope", "capability"))
                .elevated()
                .asDelegable(),
            KnownCapability.of(BACKUPS)
                .label(Microcopy.of("backups").withFilter("scope", "capability"))
                .elevated()
                .asDelegable(),
            // Phase 5: the image gate exists (InstanceImagePolicy on the write funnel),
            // so the capability registers WITH its enforcement per the no-unwired rule.
            // The grant matrix this declaration attaches is the instances access page
            // that manage/snapshots/backups already surface.
            KnownCapability.of(IMAGE_ANY)
                .label(Microcopy.of("image_any").withFilter("scope", "capability"))
                .elevated(),
            // Phase 6: the file manager exists (InstanceFiles behind the Files tab and the
            // /api/v1 file lane), so its two capabilities register WITH their enforcement
            // per the no-unwired rule. They ride the SAME grant matrix manage/snapshots/
            // backups already surface, so declaring them adds two columns to a page that is
            // already reachable and designed -- not a new unreachable surface.
            //
            // AIDEV-NOTE: read is the only ORDINARY capability on this model. It is NOT
            // owner-implied and NOT implied by write: InstanceFiles asks for exactly one of
            // the two on every call, so an operator can hand out a read-only file browser.
            KnownCapability.of(FILES_READ)
                .label(Microcopy.of("files_read").withFilter("scope", "capability"))
                .asDelegable(),
            KnownCapability.of(FILES_WRITE)
                .label(Microcopy.of("files_write").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(InstanceModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        RecordGrants.declareGrantable(GrantableModel.of(CertificateModel.MODEL_ID));
        KnownCapabilities.register(CertificateModel.MODEL_ID,
            KnownCapability.of(VIEW)
                .label(Microcopy.of("view").withFilter("scope", "capability"))
                .asDelegable()
                .asOwnerImplied(),
            // Not owner-implied: having ordered one certificate is not authority to order the
            // next. Key EXPORT and certificate UPLOAD are not capabilities at all -- hohenheim
            // terminates TLS itself so a tenant never needs the key, and an uploaded
            // certificate is unverified authority over a name.
            KnownCapability.of(REQUEST)
                .label(Microcopy.of("request").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(CertificateModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS)
                // The requester IS the owner: the column already exists because renewal
                // re-decides authority against it every sweep.
                .ownedBy(CertificateModel.REQUESTED_BY_USER_ID.getName()));
    }

    /**
     * Whether two records of one model answer to the SAME owner, which is what separates
     * a deliberate configuration from a cross-tenant seizure.
     *
     * AIDEV-NOTE: ownership is the record's set of {@link #MANAGE} grant SUBJECTS, never
     * an owner column -- InstanceModel deliberately has NO owner_principal_id, and this
     * method is THE one derivation every tier (routes, released claims, instances) answers
     * from; a second spelling is how two authorities drift. Two records an operator alone
     * controls hold no manage grants at all, so they compare equal and an admin may
     * deliberately point a wildcard at one site and carve one host out to another (a
     * shipped, dispatch-tested capability -- exact beats wildcard, and two upstreams need
     * two sites). The moment either side is TENANT-held, the subject sets differ and the
     * same shape becomes a takeover. Equality, not overlap: {A} versus {A, B} would let B
     * seize what A was serving. Mirrors WorkloadIdentity.isTenantManaged, which is the
     * same tenancy predicate one seam over.
     *
     * @return true when both records carry the same manage-grant subjects (both empty
     *         included), failing CLOSED to "different owners" when grants cannot be read
     */
    public static boolean sameOwner(@NonNull Identifier model, @NonNull Object firstId,
                                    @NonNull Object secondId) {
        // Grants key records by their stringified id, so identity folds the same way.
        if (String.valueOf(firstId).equals(String.valueOf(secondId))) {
            return true;
        }
        Set<String> first = manageSubjectsOf(model, firstId);
        Set<String> second = manageSubjectsOf(model, secondId);
        return first != null && second != null && first.equals(second);
    }

    /** Site convenience over {@link #sameOwner(Identifier, Object, Object)}. */
    public static boolean sameOwner(int firstSiteId, int secondSiteId) {
        return sameOwner(SiteModel.MODEL_ID, firstSiteId, secondSiteId);
    }

    /**
     * THE owner identity of a record: the subjects holding {@link #MANAGE} on it, spelled
     * {@code subjectType:subjectId}. An EMPTY set means operator-owned (nobody was granted
     * anything), which is why it is a legitimate value and never an error.
     *
     * AIDEV-NOTE: public because the released-claim ledger (ReleasedClaims) must STORE this
     * exact set at release time and compare a later claimant against it. It is the same
     * authority {@link #sameOwner} answers from -- a second spelling of "who owns this
     * record" is how the quarantine and the overlap refusal would end up disagreeing.
     *
     * @return the manage-grant subjects, or null when grants are unreadable (callers fail closed)
     */
    public static @Nullable Set<String> manageSubjectsOf(@NonNull Identifier model,
                                                         @NonNull Object recordId) {
        Set<String> subjects = new HashSet<>();
        try {
            for (Row grant : RecordGrants.listForRecord(model, recordId)) {
                if (MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                        && Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))) {
                    subjects.add(grant.get(RecordGrantModel.SUBJECT_TYPE)
                        + ":" + grant.get(RecordGrantModel.SUBJECT_ID));
                }
            }
        } catch (IllegalStateException notInstalled) {
            // ZenitAuth.init never ran (tools, minimal tests): no tenants can exist, so
            // every record is operator-owned and the sets are legitimately equal.
            return subjects;
        } catch (RuntimeException unreadable) {
            return null;
        }
        return subjects;
    }

    /** Site convenience over {@link #manageSubjectsOf(Identifier, Object)}. */
    public static @Nullable Set<String> manageSubjectsOf(int siteId) {
        return manageSubjectsOf(SiteModel.MODEL_ID, siteId);
    }

    /** How a packed subject set separates its entries; no subject token can contain it. */
    public static final String SUBJECT_SEPARATOR = "\n";

    /**
     * THE canonical packing of a subject set (released-claim ledger, quota bucket keys):
     * sorted and newline-joined, so two spellings of one owner set can never compare
     * unequal. EMPTY packs to "" -- the operator. A second packing beside this one is how
     * the quarantine and the quota would end up disagreeing about who an owner is.
     */
    public static @NonNull String packSubjects(@NonNull Set<String> subjects) {
        return String.join(SUBJECT_SEPARATOR, new TreeSet<>(subjects));
    }

    /** The inverse of {@link #packSubjects}; null/"" parses to the empty (operator) set. */
    public static @NonNull Set<String> parseSubjects(@Nullable Object packed) {
        if (packed == null) {
            return Set.of();
        }
        String raw = String.valueOf(packed);
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<String> subjects = new LinkedHashSet<>();
        for (String part : raw.split(SUBJECT_SEPARATOR)) {
            if (!part.isEmpty()) {
                subjects.add(part);
            }
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
     */
    public static boolean hasInstanceCapability(@NonNull AccessContext ctx, int instanceId,
                                                @NonNull String capability) {
        return ctx.hasCapability(InstanceModel.MODEL_ID, instanceId, capability);
    }

    /**
     * THE operation-funnel gate for a capability-sensitive instance act (power, snapshot,
     * backup): a TENANT-ORIGINATED call must hold the capability, while operator and
     * system work (background tasks, schedule chains re-authorized per step, seeds) passes
     * untouched. It sits on the SERVICE, not on a resource or a handler, for the reason
     * {@link TenantWrites} spells out: the HTML row action, the automation API and any
     * future caller all reach the service, and a second copy per surface is how the API
     * ends up a wider door than the UI.
     *
     * AIDEV-NOTE: the refusal is the SAME text for every capability and never says which
     * one is missing -- naming it would turn a refusal into a capability oracle. It is
     * also the same refusal a caller gets for an instance they cannot see at all, which
     * is what the API's uniform 404 is built on.
     *
     * @throws Violations {@code instance_not_permitted}
     */
    public static void requireOperationCapability(int instanceId, @NonNull String capability) {
        if (!TenantWrites.isTenantOriginated()) {
            return;
        }
        AccessContext ctx = TenantWrites.acting();
        if (ctx == null || !hasInstanceCapability(ctx, instanceId, capability)) {
            throw Violations.ofForm(Microcopy.of("instance_not_permitted")
                .withFilter("scope", "violations"));
        }
    }

    /**
     * @return null for admins (no extra constraint), else {@code ID IN (the instance ids
     *         the context holds {@code capability} on)}, matching NOTHING when there are none
     */
    public static @Nullable Criteria instanceScope(@NonNull AccessContext ctx,
                                                   @NonNull String capability) {
        return grantScope(ctx, Models.get(InstanceModel.class), InstanceModel.MODEL_ID,
            capability, InstanceModel.ID::in);
    }

    /** Every instance id the context holds {@code capability} on (walk-confirmed). */
    @NonNull
    public static Set<Integer> instanceIdsWith(@NonNull AccessContext ctx,
                                               @NonNull String capability) {
        return grantedRecordIds(ctx, InstanceModel.MODEL_ID, capability);
    }

    /**
     * The explicit creation-owner override (a PROJECT create): set by the one funnel
     * that validated the actor's membership, read by every consumer of
     * {@link #creationOwnerSubjects} -- so the quota charge, the placement decision
     * and the planted grant follow the override as ONE derivation, never three.
     */
    private static final ThreadLocal<@Nullable Set<String>> CREATION_OWNER =
        new ThreadLocal<>();

    /**
     * Run {@code body} with the creation-owner derivation pinned to {@code subjects}
     * (a validated project subject). Nesting is refused: two pending owners on one
     * thread means two funnels interleaved, which is a bug, not a use case.
     *
     * @throws IllegalStateException when a creation owner is already pinned
     */
    public static void withCreationOwner(@NonNull Set<String> subjects, @NonNull Runnable body) {
        if (CREATION_OWNER.get() != null) {
            throw new IllegalStateException("A creation owner is already pinned on this thread");
        }
        CREATION_OWNER.set(Set.copyOf(subjects));
        try {
            body.run();
        } finally {
            CREATION_OWNER.remove();
        }
    }

    /**
     * THE owner identity a NEW record created by this context will answer to: an
     * explicitly pinned owner (a validated project create) when one is active, else the
     * acting user's own subject, or the empty (operator) set for admins and system work.
     * One derivation, because the quota bucket charged at create, the placement decision
     * and the manage grant planted right after MUST name the same owner -- two spellings
     * is how a tenant's instance ends up charged to the operator's bucket.
     */
    @NonNull
    public static Set<String> creationOwnerSubjects(@Nullable AccessContext ctx) {
        Set<String> pinned = CREATION_OWNER.get();
        if (pinned != null) {
            return pinned;
        }
        if (ctx == null || isAdmin(ctx) || ctx.isAnonymous()) {
            return Set.of();
        }
        Long principalId = ctx.principalId();
        return principalId == null ? Set.of() : Set.of("user:" + principalId);
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
        return grantScope(ctx, model, SiteModel.MODEL_ID, MANAGE, forManagedIds);
    }

    /**
     * The generalized shape of {@link #managedSiteScope}: admins are unconstrained, an
     * anonymous or grant-less principal matches NOTHING, and everyone else gets the criteria
     * {@code forGrantedIds} spells over the walk-confirmed record ids.
     *
     * @param model the model being SCOPED (its {@code matchNone()}), which is not necessarily
     *        the model the capability is held on -- domains scope by their parent site
     */
    public static @Nullable Criteria grantScope(@NonNull AccessContext ctx,
                                                @NonNull Model model,
                                                @NonNull Identifier capabilityModel,
                                                @NonNull String capability,
                                                @NonNull Function<Set<Integer>, Criteria> forGrantedIds) {
        if (isAdmin(ctx)) {
            return null;
        }
        if (ctx.isAnonymous()) {
            return model.matchNone();
        }
        Set<Integer> ids = grantedRecordIds(ctx, capabilityModel, capability);
        return ids.isEmpty() ? model.matchNone() : forGrantedIds.apply(ids);
    }

    /** Request-scoped memo of the confirmed id sets, keyed by model + capability. */
    private static final IdentifierKey<Map<String, Set<Integer>>> GRANTED_RECORD_IDS =
        IdentifierKey.of("hohenheim", "granted_record_ids");

    /**
     * Every site id the context holds {@link #MANAGE} on.
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull AccessContext ctx) {
        return grantedRecordIds(ctx, SiteModel.MODEL_ID, MANAGE);
    }

    /**
     * Every record id of {@code model} the context holds {@code capability} on, for feeding
     * scope criteria (the walk decides per record and offers no enumeration, so candidates
     * come from the grant store and each one is CONFIRMED through the walk -- which
     * re-applies admin bypass and gate denial).
     *
     * Memoized per REQUEST on the conduit (the PermissionResolver WALK_CACHE idiom): panel
     * eligibility, scope criteria and the nav probes all ask per render, and grants written
     * mid-request stay next-request-effective. Conduit-less contexts run the enumeration
     * fresh.
     *
     * AIDEV-NOTE: the memo is a MAP keyed by model+capability, not one attribute per set. One
     * attribute per set is how the second consumer (dns records) quietly ends up outside the
     * budget the first consumer's test pinned.
     */
    @NonNull
    public static Set<Integer> grantedRecordIds(@NonNull AccessContext ctx,
                                                @NonNull Identifier model,
                                                @NonNull String capability) {
        String key = model + "#" + capability;
        Conduit conduit = ctx.conduit();
        if (conduit == null) {
            return enumerateGrantedIds(ctx, model, capability);
        }

        Map<String, Set<Integer>> cache = conduit.getAttribute(GRANTED_RECORD_IDS);
        if (cache == null) {
            cache = new HashMap<>();
            try {
                conduit.setAttribute(GRANTED_RECORD_IDS, cache);
            } catch (UnsupportedOperationException attributeless) {
                // A conduit without attribute storage just pays the walk each call.
            }
        }

        Set<Integer> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        Set<Integer> ids = enumerateGrantedIds(ctx, model, capability);
        cache.put(key, ids);
        return ids;
    }

    private static @NonNull Set<Integer> enumerateGrantedIds(@NonNull AccessContext ctx,
                                                             @NonNull Identifier model,
                                                             @NonNull String capability) {
        return confirmedRecordIds(ctx.principal(), model, capability,
            id -> ctx.hasCapability(model, id, capability));
    }

    /**
     * Principal-only enumeration for conduit-less contexts, confirmed through
     * the same walk via the installed WebSocket authenticator.
     */
    @NonNull
    public static Set<Integer> managedSiteIds(@NonNull Principal principal) {
        WebSocketAuthenticator authenticator = Zenit.getWebSocketAuthenticator();
        return confirmedRecordIds(principal, SiteModel.MODEL_ID, MANAGE,
            id -> authenticator.hasCapability(principal, SiteModel.MODEL_ID, id, MANAGE));
    }

    /**
     * @return the grant-derived candidate ids the walk confirms (unparseable ids skipped)
     */
    @NonNull
    private static Set<Integer> confirmedRecordIds(@NonNull Principal principal,
                                                   @NonNull Identifier model,
                                                   @NonNull String capability,
                                                   @NonNull Predicate<String> confirmedByWalk) {
        if (principal.isAnonymous()) {
            return Set.of();
        }
        Set<Integer> ids = new HashSet<>();
        for (String raw : RecordGrants.recordIds(principal, model, capability)) {
            if (!confirmedByWalk.test(raw)) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                // Grants store record ids as strings; these models all key on an integer.
            }
        }
        return ids;
    }
}
