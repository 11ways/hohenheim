package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.server.GrantableModel;
import be.elevenways.zenit.auth.server.RecordGrantCapabilityChecker;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.KnownCapability;
import be.elevenways.zenit.common.security.RecordCapabilityRules;

import static be.elevenways.hohenheim.server.auth.HohenheimAccess.BACKUPS;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.CONFIG;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.CONSOLE;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.CREDENTIALS;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.DESTROY;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.DYNDNS;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.EDIT;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.EXEC;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.FILES_READ;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.FILES_WRITE;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.IMAGE_ANY;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.MANAGE;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.POWER;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.SHELL;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.SITES_MANAGE_ALL;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.SNAPSHOTS;
import static be.elevenways.hohenheim.server.auth.HohenheimAccess.VIEW;

/**
 * The boot-time declaration of which models hold record grants, their capability vocabularies and
 * the walk's composition rules; reached through {@link HohenheimAccess#declareGrantableModels}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class HohenheimGrantPolicy {

    private HohenheimGrantPolicy() {
    }

    /**
     * The boot-time half of this policy: every model here that holds record grants (sites,
     * DNS records, instances, managed databases, git providers, access lists and
     * certificates) is declared grantable, because zenit-auth refuses a grant on an
     * undeclared model and the declaration is also what keeps the grant-cleanup hooks off
     * every other model's deletes. Each model's capability VOCABULARY (e.g. site manage is
     * delegable, so a holder may mint the {@code cap:hohenheim:site#manage} API-key scope)
     * and the walk's composition RULES land here too, so the enforcement path and the
     * delegation path can never see different policies.
     */
    static void declareGrantableModels() {
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
                .admin(HohenheimPanel.ACCESS)
                // Every-site authority without the admin permission; see SITES_MANAGE_ALL for
                // why this line belongs on THIS model and on no other one here.
                .typeLevel(SITES_MANAGE_ALL));

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
            //
            // AIDEV-NOTE: files.read, snapshots and backups were MISSING from this list
            // until 2026-08-11, and the docblock above states exactly why that was wrong:
            // each of the three is surfaced by ONE tab on the instance record page, so a
            // delegate granted only that capability was 403'd off the record and could
            // never reach the tab the grant exists for. The grant did strictly less than
            // it claimed and nothing reported it -- InstanceFilesTabGateTest caught it
            // while proving the files tab's own gate.
            KnownCapability.of(VIEW)
                .label(Microcopy.of("view").withFilter("scope", "capability"))
                .asDelegable()
                .impliedBy(MANAGE, CONSOLE, POWER, CONFIG, DESTROY,
                    FILES_READ, SNAPSHOTS, BACKUPS, SHELL),
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
            // AIDEV-NOTE: files.read is ORDINARY, alongside view/console/power (four of
            // them; KnownCapability defaults to ORDINARY, so an absent .elevated()/.admin()
            // IS the declaration -- corrected 2026-08-08, this note used to claim read was
            // the only one). What is specific to read: it is NOT owner-implied and NOT
            // implied by write. InstanceFiles asks for exactly one of the two on every
            // call, so an operator can hand out a read-only file browser.
            KnownCapability.of(FILES_READ)
                .label(Microcopy.of("files_read").withFilter("scope", "capability"))
                .asDelegable(),
            KnownCapability.of(FILES_WRITE)
                .label(Microcopy.of("files_write").withFilter("scope", "capability"))
                .elevated()
                .asDelegable(),
            // The interactive shell lands WITH its enforcing surface (InstanceShell behind
            // the Shell tab and the instance-shell WebSocket), per the no-unwired rule. It
            // rides the same subjects x capabilities matrix the verbs above already
            // surface, and it implies VIEW so a shell delegate is not 404'd off the record
            // carrying the tab -- the defect files.read/snapshots/backups shipped with.
            KnownCapability.of(SHELL)
                .label(Microcopy.of("shell").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(InstanceModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        // Managed databases: the tenant-allocation tier (Phase 5). MANAGE stays THE
        // ownership identity for exactly the reason it does on instances -- there is no
        // owner column on managed_databases, and manageSubjectsOf/sameOwner, the instance
        // quota bucket the engine is charged to (InstanceQuota.creationOwnerPackOf reads the
        // OWNING DATABASE's manage grants) and creationOwnerSubjects all read it. The
        // narrow verbs are what manage IMPLIES, exactly the instance-tier template.
        //
        // AIDEV-NOTE: this vocabulary is deliberately SHORTER than the operations the
        // tier has, because a verb lands WITH its enforcing surface and never ahead of
        // it -- declaring one attaches a subjects x capabilities grant matrix, so a
        // declared-but-unenforced verb ships an operator-editable delegation surface over
        // something nothing checks. The refusals, each with its reason:
        //
        // - restore: DatabaseService.restoreFromFile runs an UPLOADED dump as the engine
        //   superuser, and the only page that offers it (DatabaseRestorePage) also renders
        //   the plaintext credentials. Neither the arbitrary-SQL lane nor a credential-free
        //   variant of that page is built here, so there is nothing to enforce a `restore`
        //   grant ON. It stays operator-only and is the first candidate when a delegated
        //   restore surface is actually designed.
        // - config: DatabaseResource is updatable() == false -- the record is immutable
        //   after create by design (it describes a provisioned container), so no edit
        //   operation exists for the verb to gate.
        // - power: the engine is a generatedOnly() DatabaseContainerKind instance, and
        //   ManageInstanceResource excludes generated rows, so no tenant path reaches a
        //   start/stop of it at all. A database is allocated and destroyed, not powered.
        // - exec: NEVER. Backup and restore are IMPLEMENTED by exec'ing into the engine
        //   container; offering the verb would be offering a superuser shell on the host.
        RecordGrants.declareGrantable(GrantableModel.of(DatabaseModel.MODEL_ID));
        KnownCapabilities.register(DatabaseModel.MODEL_ID,
            KnownCapability.of(MANAGE)
                .label(Microcopy.of("manage").withFilter("scope", "capability"))
                .elevated()
                .asDelegable(),
            KnownCapability.of(VIEW)
                .label(Microcopy.of("view").withFilter("scope", "capability"))
                .asDelegable()
                .impliedBy(MANAGE, CREDENTIALS, BACKUPS, DESTROY),
            KnownCapability.of(CREDENTIALS)
                .label(Microcopy.of("credentials").withFilter("scope", "capability"))
                .elevated()
                .asDelegable()
                .impliedBy(MANAGE),
            // AIDEV-NOTE: backups IS implied by manage here while it is NOT on instances,
            // and the difference is deliberate rather than an oversight. On instances the
            // umbrella had to stop where it did because widening it would have silently
            // handed the capability to every ALREADY-STORED manage grant. This model has
            // no stored grants to widen -- the vocabulary ships with the surface -- so the
            // umbrella is chosen on the merits: a database's owner backing up their own
            // database is the ordinary case, not a delegation.
            KnownCapability.of(BACKUPS)
                .label(Microcopy.of("backups").withFilter("scope", "capability"))
                .elevated()
                .asDelegable()
                .impliedBy(MANAGE),
            KnownCapability.of(DESTROY)
                .label(Microcopy.of("destroy").withFilter("scope", "capability"))
                .elevated()
                .asDelegable()
                .impliedBy(MANAGE));
        RecordGrantCapabilityChecker.declareRules(DatabaseModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        // Git providers: MANAGE is the WHOLE vocabulary, and that is a decision. The row
        // is a credential store, so there is no read-only half worth granting -- either a
        // subject owns the installation (edit it, test it, delete it) or it merely USES
        // one, and using is not a grant question: a provider is offered to a picker when
        // it is SHARED or when the principal manages it (see gitProviderScope). The
        // narrow verbs instances have (console/power/...) have no analogue here.
        RecordGrants.declareGrantable(GrantableModel.of(GitProviderModel.MODEL_ID));
        KnownCapabilities.register(GitProviderModel.MODEL_ID,
            KnownCapability.of(MANAGE)
                .label(Microcopy.of("manage").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(GitProviderModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        // Access lists: MANAGE is the whole vocabulary, the git-provider decision one row
        // up applied verbatim -- either a subject owns the policy (edit its rules, delete
        // it) or it merely ATTACHES it, and attaching is not a grant question: a list is
        // offered when it is SHARED or when the principal manages it (accessListScope).
        // The rule ROWS deliberately have no grant surface of their own; they answer to
        // their parent list, exactly like site domains answer to their site.
        RecordGrants.declareGrantable(GrantableModel.of(AccessListModel.MODEL_ID));
        KnownCapabilities.register(AccessListModel.MODEL_ID,
            KnownCapability.of(MANAGE)
                .label(Microcopy.of("manage").withFilter("scope", "capability"))
                .elevated()
                .asDelegable());
        RecordGrantCapabilityChecker.declareRules(AccessListModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS));

        RecordGrants.declareGrantable(GrantableModel.of(CertificateModel.MODEL_ID));
        // AIDEV-NOTE: VIEW is the WHOLE certificate vocabulary, and that is a decision.
        // Key EXPORT and certificate UPLOAD are not capabilities at all -- hohenheim
        // terminates TLS itself so a tenant never needs the key, and an uploaded
        // certificate is unverified authority over a name. ORDERING is not one either:
        // see the note beside HohenheimAccess.DYNDNS for why the struck `request` capability
        // could never have been the authority CertificateAuthority already decides by name
        // coverage.
        KnownCapabilities.register(CertificateModel.MODEL_ID,
            KnownCapability.of(VIEW)
                .label(Microcopy.of("view").withFilter("scope", "capability"))
                .asDelegable()
                .asOwnerImplied());
        RecordGrantCapabilityChecker.declareRules(CertificateModel.MODEL_ID,
            RecordCapabilityRules.create()
                .gate(ManagePanel.ACCESS)
                .admin(HohenheimPanel.ACCESS)
                // The requester IS the owner: the column already exists because renewal
                // re-decides authority against it every sweep.
                .ownedBy(CertificateModel.REQUESTED_BY_USER_ID.getName()));
    }
}
