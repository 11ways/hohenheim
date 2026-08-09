package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.SortOrder;

import java.util.List;

/**
 * A single managed runtime unit (the instance tier's record): one container today,
 * Incus containers and VMs later. The FOURTH consumer of the shared runtime machinery
 * (owner labels, port ledger, reconciler, canonical host key) -- never a parallel
 * authority beside Site, Stack and Database.
 *
 * AIDEV-NOTE: deliberately NO owner_principal_id column -- ownership is grant-derived
 * (HohenheimAccess.sameOwner/manageSubjectsOf over the manage-grant subject set), and a
 * column would be a second authority that drifts the first time a grant changes. The
 * "no fence columns" stance this note originally recorded was superseded when HostLeases
 * landed: CLAIM_FENCE below IS enforced (every outcome write is guarded on it), so it is
 * not the reads-like-enforcement-but-enforces-nothing shape the plan bans.
 */
public class InstanceModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "instance");
    public static final Schema SCHEMA = new Schema();

    /** {@link #STATUS}: record exists, nothing was ever deployed. */
    public static final String STATUS_CREATED = "created";

    /**
     * {@link #STATUS}: deployed and started, awaiting the template's {@code
     * readiness_line} on the console before it counts as Running (Phase 5's
     * console-matcher clause). Only instances with a readiness line pass through here.
     */
    public static final String STATUS_STARTING = "starting";

    /** {@link #STATUS}: last deploy reached a running container. */
    public static final String STATUS_RUNNING = "running";

    /** {@link #STATUS}: deliberately stopped (or destroyed under a soft delete). */
    public static final String STATUS_STOPPED = "stopped";

    /** {@link #STATUS}: the last runtime operation failed; the daemon may disagree. */
    public static final String STATUS_ERROR = "error";

    /**
     * {@link #STATUS}: a snapshot or backup capture is in flight (settle-then-refuse:
     * deploy is refused while set, so a concurrent start cannot corrupt the capture).
     */
    public static final String STATUS_CAPTURING = "capturing";

    /**
     * {@link #STATUS}: a restore is replacing this instance's volumes (the Pterodactyl
     * {@code restoring_backup} lesson: a protected status gating power actions).
     */
    public static final String STATUS_RESTORING = "restoring";

    /**
     * {@link #STATUS}: a cold migration is moving this workload to the host named by
     * {@link #MIGRATE_TARGET_ID}. Protected like capture/restore (deploy and stop
     * refuse); {@code InstanceMigrations.recoverInterrupted} settles a row left here
     * by a killed controller.
     */
    public static final String STATUS_MIGRATING = "migrating";

    /**
     * The statuses under which a workload may be ON THE BRIDGE, and therefore the ones an
     * isolation sweep must verify.
     *
     * AIDEV-NOTE: ONE list, because the two isolation sweeps disagreed about it and the
     * disagreement was an oversight rather than a decision. {@code VerifyIncusIsolation}
     * filtered {@code running} alone while its Docker twin filtered running OR starting, so
     * an Incus workload was never kernel-verified while it sat in {@code starting} -- which
     * deploy stamps for every template carrying a readiness line. {@code error} was missing
     * from BOTH, and it is the status most likely to name a live-but-unwatched guest: a
     * readiness timeout stamps error and stops NOTHING (InstanceConsoles.arm), so the guest
     * keeps running with its record claiming failure. The status column's own words are
     * "the last runtime operation failed; the daemon may disagree".
     *
     * DELIBERATELY EXCLUDED: {@code capturing}, {@code restoring} and {@code migrating}.
     * Those guests can be live too, but they are the protected in-flight statuses whose
     * declared contract refuses stop and deploy, and an isolation sweep's terminal action IS
     * a stop -- letting a firewall sweep abort a backup or a migration would trade one
     * silent failure for a louder one. Their exposure is bounded by the operation's own
     * lifetime and by the next sweep after it settles. {@code created} and {@code stopped}
     * name no guest at all.
     */
    public static final List<String> LIVE_GUEST_STATUSES =
        List.of(STATUS_RUNNING, STATUS_STARTING, STATUS_ERROR);

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());

    // User data, NOT localized (the plan's explicit call: names are the user's own words).
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name")
        .label(HohenheimFormCopy.label("name"))
        .build());

    // ONE discriminator: the kind implies the runtime (docker_container now; incus_container
    // and vm reserved). Values enumerate the registry live; stored value = "hohenheim:<kind>".
    public static final EnumField KIND = SCHEMA.addField(
        RegistryEnumField.builder("kind")
            .registry(InstanceKindRegistry.REGISTRY)
            .label(HohenheimFormCopy.label("kind"))
            .help(HohenheimFormCopy.help("instance_kind"))
            .build());

    // Polymorphic per-kind settings. AIDEV-NOTE: the dynamic (schemaFrom) form entry
    // REWRITES this whole map on every admin save (the SiteModel.SECURITY_REPORT_TOKEN
    // lesson) -- anything that must survive an admin save gets a COLUMN, never a key here.
    public static final SchemaField SETTINGS = SCHEMA.addField(
        SchemaField.builder("settings")
            .schemaFrom("kind")
            .label(HohenheimFormCopy.label("settings"))
            .build());

    // The host FK (servers.id). Every write folds through ServerModel.canonicalServerId
    // (the beforeValidate hook below) -- never a re-spelling; null means the local daemon.
    public static final IntegerField SERVER_ID = SCHEMA.addField(
        IntegerField.builder().name("server_id")
            .label(HohenheimFormCopy.label("server"))
            .build());

    public static final EnumField STATUS = SCHEMA.addField(EnumField.builder("status")
        .value(STATUS_CREATED, v -> v.displayName("Created").icon("circle")
            .label(Microcopy.of("created").withFilter("scope", "instance_status")).color("gray"))
        .value(STATUS_STARTING, v -> v.displayName("Starting").icon("hourglass-half")
            .label(Microcopy.of("starting").withFilter("scope", "instance_status")).color("blue"))
        .value(STATUS_RUNNING, v -> v.displayName("Running").icon("circle-play")
            .label(Microcopy.of("running").withFilter("scope", "instance_status")).color("green"))
        .value(STATUS_STOPPED, v -> v.displayName("Stopped").icon("circle-stop")
            .label(Microcopy.of("stopped").withFilter("scope", "instance_status")).color("orange"))
        .value(STATUS_ERROR, v -> v.displayName("Error").icon("circle-exclamation")
            .label(Microcopy.of("error").withFilter("scope", "instance_status")).color("red"))
        .value(STATUS_CAPTURING, v -> v.displayName("Capturing").icon("camera")
            .label(Microcopy.of("capturing").withFilter("scope", "instance_status")).color("blue"))
        .value(STATUS_RESTORING, v -> v.displayName("Restoring").icon("clock-rotate-left")
            .label(Microcopy.of("restoring").withFilter("scope", "instance_status")).color("blue"))
        .value(STATUS_MIGRATING, v -> v.displayName("Migrating").icon("arrow-right-arrow-left")
            .label(Microcopy.of("migrating").withFilter("scope", "instance_status")).color("blue"))
        .defaultValue(STATUS_CREATED)
        .build());

    /** {@link #INSTALL_STATE}: this instance has no install lifecycle (no template step). */
    public static final String INSTALL_NONE = "none";

    /** {@link #INSTALL_STATE}: the template declares an install step that has not run yet. */
    public static final String INSTALL_PENDING = "pending";

    /** {@link #INSTALL_STATE}: an install/reinstall is in flight (or was interrupted mid-run). */
    public static final String INSTALL_INSTALLING = "installing";

    /** {@link #INSTALL_STATE}: the template's install step completed. */
    public static final String INSTALL_INSTALLED = "installed";

    /** {@link #INSTALL_STATE}: the install step failed; retry re-runs it, variables untouched. */
    public static final String INSTALL_FAILED = "failed";

    /**
     * The template this instance was created from (instance_templates.id), or null for a
     * template-less instance. Together with the settings image it is what
     * InstanceImagePolicy judges: a tenant instance either carries an APPROVED template's
     * image or its actor holds {@code image_any}.
     */
    public static final IntegerField TEMPLATE_ID = SCHEMA.addField(
        IntegerField.builder().name("template_id")
            .label(HohenheimFormCopy.label("template"))
            .build());

    /**
     * Durable install/reinstall state -- a COLUMN, not a settings key, because the
     * dynamic settings form entry rewrites the whole map on every admin save. An
     * interrupted run leaves {@code installing} behind as evidence; retry resumes it.
     */
    public static final EnumField INSTALL_STATE = SCHEMA.addField(EnumField.builder("install_state")
        .value(INSTALL_NONE, v -> v.displayName("None")
            .label(Microcopy.of("none").withFilter("scope", "install_state")).color("gray"))
        .value(INSTALL_PENDING, v -> v.displayName("Install pending").icon("clock")
            .label(Microcopy.of("pending").withFilter("scope", "install_state")).color("orange"))
        .value(INSTALL_INSTALLING, v -> v.displayName("Installing").icon("hourglass-half")
            .label(Microcopy.of("installing").withFilter("scope", "install_state")).color("blue"))
        .value(INSTALL_INSTALLED, v -> v.displayName("Installed").icon("circle-check")
            .label(Microcopy.of("installed").withFilter("scope", "install_state")).color("green"))
        .value(INSTALL_FAILED, v -> v.displayName("Install failed").icon("circle-exclamation")
            .label(Microcopy.of("install_failed").withFilter("scope", "install_state")).color("red"))
        .defaultValue(INSTALL_NONE)
        .build());

    /** {@link #CRASH_POLICY}: an unexpected exit stamps Stopped and nothing restarts. */
    public static final String CRASH_NONE = "none";

    /**
     * {@link #CRASH_POLICY}: ANY exit not preceded by an observed stop (command or
     * operator stop) is a crash -- clean exit code included, the game-template default
     * per the plan -- and the console watcher redeploys it, flap-protected.
     */
    public static final String CRASH_RESTART = "restart";

    /**
     * What the console watcher does when the workload exits without an observed stop.
     * Only enforced while a console session is attached (readiness/stop matcher, crash
     * policy or an open admin console); without one, nothing observes the exit.
     */
    public static final EnumField CRASH_POLICY = SCHEMA.addField(EnumField.builder("crash_policy")
        .value(CRASH_NONE, v -> v.displayName("None")
            .label(Microcopy.of("none").withFilter("scope", "crash_policy")).color("gray"))
        .value(CRASH_RESTART, v -> v.displayName("Restart on crash").icon("rotate")
            .label(Microcopy.of("restart").withFilter("scope", "crash_policy")).color("green"))
        .defaultValue(CRASH_NONE)
        .label(HohenheimFormCopy.label("crash_policy"))
        .help(HohenheimFormCopy.help("crash_policy"))
        .build());

    /** Tail of the failed install run's output (operator diagnosis; cleared on success). */
    public static final TextField INSTALL_ERROR = SCHEMA.addField(
        TextField.builder().name("install_error").filterable(false).build());

    /** The backup target (backup_targets.id) exports of this instance are written to. */
    public static final IntegerField BACKUP_TARGET_ID = SCHEMA.addField(
        IntegerField.builder().name("backup_target_id")
            .label(HohenheimFormCopy.label("backup_target"))
            .build());

    /**
     * The environment (environments.id) grouping this instance, or null. GROUPING
     * only, never authority: ownership stays grant-derived, and the ProjectGuards
     * write hook refuses an environment whose project does not OWN this instance --
     * so the grouping can never disagree with the grants.
     */
    public static final IntegerField ENVIRONMENT_ID = SCHEMA.addField(
        IntegerField.builder().name("environment_id")
            .label(HohenheimFormCopy.label("environment"))
            .build());

    /**
     * The quota bucket this record's reservation was CHARGED to (stamped by the
     * reserve hook, read back by the release on the soft-delete transition).
     *
     * AIDEV-NOTE: bookkeeping of the reservation, NEVER an ownership authority --
     * ownership stays grant-derived (HohenheimAccess.manageSubjectsOf). Grants added
     * after create move ownership without moving this column; releasing against the
     * CHARGED bucket instead of the current derivation is what keeps the ledger's
     * counts exact when they drift apart.
     */
    public static final StringField QUOTA_BUCKET = SCHEMA.addField(
        StringField.builder().name("quota_bucket").filterable(false).build());

    /**
     * The memory (MB) booked for this workload on its HOST's capacity bucket, stamped by
     * InstanceCapacity at the write that took it.
     *
     * AIDEV-NOTE: the same stamp-and-release-the-stamp discipline as QUOTA_BUCKET, for the
     * same reason: settings change, and a release recomputed from the CURRENT settings
     * would hand back a different number than was taken and drift the host budget every
     * edit. A migration moves this charge between host buckets rather than re-deriving it.
     */
    public static final IntegerField CAPACITY_MB = SCHEMA.addField(
        IntegerField.builder().name("capacity_mb").filterable(false).build());

    /**
     * The memory (MB) booked for this workload against its OWNER's memory budget, stamped
     * by InstanceQuota at the write that took it.
     *
     * AIDEV-NOTE: a SECOND amount stamp beside CAPACITY_MB, holding the same number today,
     * and deliberately not shared with it. CAPACITY_MB is the HOST booking and is 0 (or
     * absent) whenever the row has no host -- a hostless instance books nothing on any
     * machine, but it still declares memory its owner is charged for. Reading the host
     * stamp for the owner release would hand back 0 for exactly those rows and lock the
     * owner out one workload at a time, which is the accounting drift both stamps exist to
     * prevent.
     */
    public static final IntegerField QUOTA_MEMORY_MB = SCHEMA.addField(
        IntegerField.builder().name("quota_memory_mb").filterable(false).build());

    /**
     * The owner disk-GB bucket this workload's ROOT disk reservation was charged to,
     * stamped by InstanceRootDiskQuota at the write that took it.
     *
     * AIDEV-NOTE: only the BUCKET is stamped, not the amount -- unlike CAPACITY_MB. The
     * charged amount IS {@code settings.root_disk_gb}, and every write reconciles the
     * delta between the stored and the staged value, so the two cannot drift apart. The
     * bucket can: grants move ownership without touching the row.
     */
    public static final StringField ROOT_DISK_BUCKET = SCHEMA.addField(
        StringField.builder().name("root_disk_bucket").filterable(false).build());

    /**
     * OBSERVED root-disk bytes in use, stamped by the disk sweeper.
     *
     * AIDEV-NOTE: NULL means NOT MEASURED, never empty. Only a driver implementing
     * {@code RootDiskUsageSupport} stamps these -- Docker enforces no root quota and
     * measures none, so its instances keep null forever and every consumer must read null
     * as "no news" instead of computing a percentage from zeros.
     */
    public static final LongField DISK_USED_BYTES = SCHEMA.addField(
        LongField.builder("disk_used_bytes").filterable(false).build());

    /** The ENFORCED root-disk ceiling the daemon reports, or 0 when there is none. */
    public static final LongField DISK_LIMIT_BYTES = SCHEMA.addField(
        LongField.builder("disk_limit_bytes").filterable(false).build());

    /** When the two figures above were last observed; null while never measured. */
    public static final DateTimeField DISK_OBSERVED_AT = SCHEMA.addField(
        DateTimeField.builder().name("disk_observed_at").build());

    /**
     * The RESOLVED image identity the workload actually runs (incus: the image
     * fingerprint behind {@code volatile.base_image}), recorded at deploy. A mutable
     * alias is not a deployment identity: recreating an absent workload uses this pin,
     * and the pin is cleared when the DECLARED image changes (HohenheimWriteHooks).
     */
    public static final StringField IMAGE_FINGERPRINT = SCHEMA.addField(
        StringField.builder().name("image_fingerprint").filterable(false).build());

    /**
     * Attribution of an instance a PRODUCT TIER authored (the GeneratedRows discipline
     * shared with generated DNS records and instance config files): {@code generated_by}
     * is the source token ({@code "site"}), {@code generated_for_model}/{@code _for_id}
     * name the OWNING product record. Attributed instances are the lowered runtime
     * resources of that record -- managed exclusively through its own surface, refused
     * read-only everywhere else by the installGuards write funnel, and excluded from the
     * standalone instance UI/API so no second UI exists over the same records.
     */
    public static final StringField GENERATED_BY = SCHEMA.addField(
        StringField.builder().name("generated_by").filterable(false).build());

    /** Model id of the owning product record ({@code hohenheim:site}). */
    public static final StringField GENERATED_FOR_MODEL = SCHEMA.addField(
        StringField.builder().name("generated_for_model").filterable(false).build());

    /** Primary key of the owning record inside {@link #GENERATED_FOR_MODEL}. */
    public static final IntegerField GENERATED_FOR_ID = SCHEMA.addField(
        IntegerField.builder().name("generated_for_id").build());

    public static final DateTimeField GENERATED_AT = SCHEMA.addField(
        DateTimeField.builder().name("generated_at").build());

    /** {@link #RUNTIME_ROLE}: the release that answers for its owner's traffic. */
    public static final String ROLE_SERVING = "serving";

    /** {@link #RUNTIME_ROLE}: a release being deployed/probed; not yet trusted with traffic. */
    public static final String ROLE_CANDIDATE = "candidate";

    /** {@link #RUNTIME_ROLE}: a superseded release RETAINED as the rollback target. */
    public static final String ROLE_RETIRED = "retired";

    /**
     * Which release of its owner this instance is (the health-gated release wave). A
     * standalone instance is trivially its own serving release, which the default states;
     * only site-attributed rows ever carry the other two values, written exclusively by
     * SiteReleases inside the GeneratedRows system scope.
     */
    public static final EnumField RUNTIME_ROLE = SCHEMA.addField(EnumField.builder("runtime_role")
        .value(ROLE_SERVING, v -> v.displayName("Serving").icon("circle-play")
            .label(Microcopy.of("serving").withFilter("scope", "runtime_role")).color("green"))
        .value(ROLE_CANDIDATE, v -> v.displayName("Candidate").icon("stethoscope")
            .label(Microcopy.of("candidate").withFilter("scope", "runtime_role")).color("blue"))
        .value(ROLE_RETIRED, v -> v.displayName("Retired").icon("box-archive")
            .label(Microcopy.of("retired").withFilter("scope", "runtime_role")).color("gray"))
        .defaultValue(ROLE_SERVING)
        .build());

    /**
     * The controller fence of the last recorded runtime outcome. Every runtime-outcome
     * write is conditional on it ({@code claim_fence IS NULL OR claim_fence <= :myFence})
     * and stamps it; a stale controller's write matches ZERO rows, and zero rows is a
     * hard failure, never a shrug (InstanceService.stampGuarded).
     */
    public static final LongField CLAIM_FENCE = SCHEMA.addField(
        LongField.builder("claim_fence").filterable(false).build());

    /**
     * The destination host (servers.id) of an in-flight cold migration; null outside
     * one. Together with {@link #SERVER_ID} this makes an interrupted migration
     * settleable without a scan: the record's host is the data authority until the
     * one-statement handoff repoints it, so recovery either rolls the copy on this
     * host back or completes the handoff -- never both, never neither.
     */
    public static final IntegerField MIGRATE_TARGET_ID = SCHEMA.addField(
        IntegerField.builder().name("migrate_target_id").filterable(false).build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());
    public static final DateTimeField DELETED_AT = SCHEMA.addField(DateTimeField.builder().name("deleted_at").build());

    static {
        SCHEMA.setDisplayFields(NAME);
        // Soft delete by hand (the SiteModel shape): DELETED_AT is lifecycle state, and the
        // grant declaration's liveWhen predicate keys on it (HohenheimAccess).
        SCHEMA.addLifecycleField(DELETED_AT);
        // Fold any host spelling (row id, name, registry key) onto THE canonical servers.id
        // at write time -- a fifth spelling of "which host" is exactly what C3 removed.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row != null && row.has(SERVER_ID.getName())
                    && row.get(SERVER_ID.getName()) != null) {
                row.set(SERVER_ID, ServerModel.canonicalServerId(row.get(SERVER_ID.getName())));
            }
        });
        // A delete carries only CRITERIA (row is null in the hook context), so the doomed
        // owners are captured before the rows disappear and their port claims parked in
        // "releasing" after -- an afterRemove-only hook would silently release NOTHING and
        // the ports would be permanently unclaimable.
        SCHEMA.addBeforeRemoveHook(PortLedger::captureDoomedOwners);
        SCHEMA.addAfterRemoveHook(PortLedger::releaseDoomedOwners);
    }

    /** Every instance that is not soft-deleted, newest first. */
    public List<Row> findLive() {
        return find()
            .where(DELETED_AT.isNull())
            .orderBy(CREATED_AT, SortOrder.DESC)
            .all();
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Instance"; }

    @Override
    public String getTableName() { return "instances"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
