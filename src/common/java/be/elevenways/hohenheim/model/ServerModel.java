package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.net.IpLiterals;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.datasource.context.SaveToDatasource;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A Docker host the platform can manage: the implicit {@code local} daemon, or a remote one
 * reached over SSH ({@code mode = "ssh"}, {@code ssh_target} like {@code user@host}). The basis of
 * the multi-server inventory; {@code DockerClient}s are built per server from these records.
 */
public class ServerModel extends Model {

    public static final Identifier MODEL_ID = Identifier.of("hohenheim", "server");
    public static final Schema SCHEMA = new Schema();

    /** {@link #MODE} value for the implicit local Docker daemon. */
    public static final String MODE_LOCAL = "local";

    /** {@link #MODE} value for a remote daemon reached over SSH. */
    public static final String MODE_SSH = "ssh";

    /** {@link #RUNTIME}: the host runs a Docker daemon (unix socket locally, ssh remotely). */
    public static final String RUNTIME_DOCKER = "docker";

    /** {@link #RUNTIME}: the host runs an Incus daemon (unix socket, or HTTPS + client cert). */
    public static final String RUNTIME_INCUS = "incus";

    /** {@link #ADMISSION}: enrolled, never (successfully) probed or never admitted -- no placement. */
    public static final String ADMISSION_BLOCKED = "blocked";

    /** {@link #ADMISSION}: preflight passed and an operator admitted the host for placement. */
    public static final String ADMISSION_ADMITTED = "admitted";

    /** {@link #ADMISSION}: admitted before, now refusing NEW placement (existing workloads stay). */
    public static final String ADMISSION_CORDONED = "cordoned";

    /** {@link #POSTURE}: operator-owned workloads only; a hostile tenant workload is refused. */
    public static final String POSTURE_TRUSTED_ONLY = "trusted_only";

    /** {@link #POSTURE}: the whole host belongs to one tenant. */
    public static final String POSTURE_DEDICATED = "dedicated";

    /** {@link #POSTURE}: shared container multi-tenancy, an explicit operator risk decision. */
    public static final String POSTURE_SHARED_CONTAINER = "shared_container";

    /** {@link #POSTURE}: VM-isolated multi-tenancy (reserved for the Incus tier). */
    public static final String POSTURE_VM_ISOLATED = "vm_isolated";

    public static final IntegerField ID = SCHEMA.addField(IntegerField.builder().name("id").build());
    public static final StringField NAME = SCHEMA.addField(StringField.builder().name("name").build());
    public static final EnumField MODE = SCHEMA.addField(EnumField.builder("mode")
        .value(MODE_LOCAL, v -> v.displayName("Local")
            .label(Microcopy.of(MODE_LOCAL).withFilter("scope", "host_mode"))
            .icon("house").color("teal"))
        .value(MODE_SSH, v -> v.displayName("SSH")
            .label(Microcopy.of(MODE_SSH).withFilter("scope", "host_mode"))
            .icon("terminal").color("indigo"))
        .build());
    public static final StringField SSH_TARGET = SCHEMA.addField(StringField.builder().name("ssh_target").build());

    /**
     * The DECLARED runtime this host runs, the discriminator every per-host dispatch
     * (client construction, preflight battery, placement) reads. Docker hosts connect
     * per {@link #MODE}/{@link #SSH_TARGET}; Incus hosts connect per {@link #INCUS_URL}.
     *
     * AIDEV-NOTE: Incus's TLS trust store IS the node-identity story earlier audits
     * wanted a bespoke agent for: enrolling this controller's client certificate on the
     * daemon (trust token or incus config trust) gives the pair a mutual, revocable,
     * per-node identity -- the client cert names this controller to the host, the pinned
     * server cert names the host to this controller. No agent needed.
     */
    public static final EnumField RUNTIME = SCHEMA.addField(EnumField.builder("runtime")
        .value(RUNTIME_DOCKER, v -> v.displayName("Docker").icon("box")
            .label(Microcopy.of("docker").withFilter("scope", "host_runtime")).color("blue"))
        .value(RUNTIME_INCUS, v -> v.displayName("Incus").icon("cubes")
            .label(Microcopy.of("incus").withFilter("scope", "host_runtime")).color("green"))
        .defaultValue(RUNTIME_DOCKER)
        .build());

    /**
     * How the Incus daemon is reached: {@code https://host:8443} (remote, TLS client
     * certificate + pinned server certificate) or {@code unix://} / blank for the local
     * socket. Meaningless for {@link #RUNTIME_DOCKER} hosts and kept null there.
     */
    public static final StringField INCUS_URL = SCHEMA.addField(
        StringField.builder().name("incus_url").nullable(true)
            .label(HohenheimFormCopy.label("incus_url"))
            .help(HohenheimFormCopy.help("incus_url"))
            .build());

    /**
     * The version of the shared-container risk warning an acknowledgement answers for.
     * Bump it when the WARNING TEXT changes materially: every stored acknowledgement of
     * an older version goes stale with no write to any row, and the operator has to
     * accept the new statement before the host takes another tenant container.
     *
     * AIDEV-NOTE: forgetting to bump is closed by a GUARD, not by discipline --
     * {@code HostPostureAcknowledgementTest} pins the sha256 of the shipped en and nl
     * {@code server.acknowledge_body} messages against this number and fails with a
     * two-way instruction (bump = the meaning changed, re-pin = the wording did not).
     * Material-versus-cosmetic stays a reviewed human call; what the guard removes is the
     * possibility of neither happening.
     */
    public static final int POSTURE_WARNING_VERSION = 1;

    /**
     * The host's declared isolation posture. Data the allocator reads, never operator
     * memory.
     *
     * AIDEV-NOTE: the shared_container acknowledgement record the plan requires (actor,
     * timestamp, warning version) IS built now -- see {@link #ACKNOWLEDGED_POSTURE} and
     * its four siblings, {@link #postureAcknowledged}, and
     * {@code HostPostureAcknowledgement}. Superseding the earlier note here: setting
     * shared_container through the form is still an ordinary admin edit and deliberately
     * stays one, but it no longer GRANTS anything -- the posture only starts accepting
     * hostile-tenant containers once the separate acknowledgement act names an actor.
     */
    public static final EnumField POSTURE = SCHEMA.addField(EnumField.builder("posture")
        .value(POSTURE_TRUSTED_ONLY, v -> v.displayName("Trusted only").icon("user-shield")
            .label(Microcopy.of("trusted_only").withFilter("scope", "host_posture")).color("teal"))
        .value(POSTURE_DEDICATED, v -> v.displayName("Dedicated").icon("user-lock")
            .label(Microcopy.of("dedicated").withFilter("scope", "host_posture")).color("indigo"))
        .value(POSTURE_SHARED_CONTAINER, v -> v.displayName("Shared containers").icon("cubes")
            .label(Microcopy.of("shared_container").withFilter("scope", "host_posture")).color("orange"))
        .value(POSTURE_VM_ISOLATED, v -> v.displayName("VM isolated").icon("boxes-stacked")
            .label(Microcopy.of("vm_isolated").withFilter("scope", "host_posture")).color("green"))
        .defaultValue(POSTURE_TRUSTED_ONLY)
        .label(HohenheimFormCopy.label("posture")).help(HohenheimFormCopy.help("posture"))
        .build());

    /**
     * Whether this host accepts placement. Defaults to {@code blocked}: a host with no
     * successful preflight is never silently trusted, including hosts enrolled before
     * preflight existed. Transitions are operator ACTIONS (admit/cordon), never form
     * fields.
     *
     * AIDEV-NOTE: still no {@code draining} token, now DELIBERATELY (superseding the
     * "no transfer mechanism exists" reasoning -- InstanceMigrations shipped the cold
     * transfer 2026-08-06). Drain is an OPERATION over a cordoned host, not a state:
     * it runs synchronously under the operator's action and ends complete or loudly
     * partial, so a stored token would only be a second authority that could claim
     * "emptying" while nothing empties.
     */
    public static final EnumField ADMISSION = SCHEMA.addField(EnumField.builder("admission")
        .value(ADMISSION_BLOCKED, v -> v.displayName("Blocked").icon("circle-xmark")
            .label(Microcopy.of("blocked").withFilter("scope", "host_admission")).color("red"))
        .value(ADMISSION_ADMITTED, v -> v.displayName("Admitted").icon("circle-check")
            .label(Microcopy.of("admitted").withFilter("scope", "host_admission")).color("green"))
        .value(ADMISSION_CORDONED, v -> v.displayName("Cordoned").icon("circle-pause")
            .label(Microcopy.of("cordoned").withFilter("scope", "host_admission")).color("orange"))
        .defaultValue(ADMISSION_BLOCKED)
        .label(HohenheimFormCopy.label("admission")).help(HohenheimFormCopy.help("admission"))
        .build());

    /** The stored preflight report: one JSON map of named checks plus probed facts. */
    public static final SchemaField CAPABILITIES = SCHEMA.addField(
        SchemaField.builder("capabilities").build());

    /** When the last full preflight ran (success or failure). */
    public static final DateTimeField PROBED_AT = SCHEMA.addField(
        DateTimeField.builder().name("probed_at").build());

    /** Whether the last preflight passed every REQUIRED check; the admit gate reads this. */
    public static final BooleanField PREFLIGHT_OK = SCHEMA.addField(
        BooleanField.builder("preflight_ok").defaultValue(false).build());

    /**
     * What the filesystem under this host's {@link #VOLUME_ROOT} can do, DETECTED by the
     * preflight probe.
     *
     * AIDEV-NOTE: per DATA ROOT, not per host (phase-0 design section 1): both live twins
     * run ext4 roots with a separate btrfs device, so "this host has btrfs somewhere" is
     * not an answer to "can this volume have a quota". An unrecognised filesystem stores
     * {@code none} and the placement gate then refuses workspaces and applications by
     * name, which is the whole point of storing it rather than probing at deploy time.
     */
    public static final EnumField VOLUME_BACKEND = SCHEMA.addField(
        VolumeBackend.fieldBuilder("volume_backend")
            .label(HohenheimFormCopy.label("volume_backend"))
            .help(HohenheimFormCopy.help("volume_backend"))
            .build());

    /** The directory that was probed ({@code <data_path>/volumes}), stored as evidence. */
    public static final StringField VOLUME_ROOT = SCHEMA.addField(
        StringField.builder().name("volume_root").nullable(true)
            .label(HohenheimFormCopy.label("volume_root")).build());

    /** What the probe actually read, so a {@code none} verdict names its own reason. */
    public static final TextField VOLUME_BACKEND_DETAIL = SCHEMA.addField(
        TextField.builder().name("volume_backend_detail").nullable(true).build());

    /** When the volume-backend probe last ran; null = never probed, which is not the same as none. */
    public static final DateTimeField VOLUME_PROBED_AT = SCHEMA.addField(
        DateTimeField.builder().name("volume_probed_at").build());

    /** Last time any probe (preflight, explicit probe action, reconcile sweep) reached the daemon. */
    public static final DateTimeField LAST_SEEN_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_seen_at").build());

    /** Typed failure class of the last failed probe (HostProbe.FailureKind token), null when healthy. */
    public static final StringField LAST_ERROR_KIND = SCHEMA.addField(
        StringField.builder().name("last_error_kind").nullable(true).build());

    /** Human detail of the last failed probe, null when healthy. */
    public static final TextField LAST_ERROR = SCHEMA.addField(
        TextField.builder().name("last_error").nullable(true).build());

    /**
     * When this host's identity was found to CONTRADICT a pin; null means never, and only
     * a repin clears it.
     *
     * AIDEV-NOTE: this column exists because {@link #LAST_ERROR_KIND} used to carry the
     * quarantine verdict as well as the last transient probe outcome, and one column
     * cannot be both. Every {@code HostProbe.recordSuccess} and every weaker
     * {@code recordFailure} overwrites the transient half by design, so a host quarantined
     * by a TLS-pin or host-key contradiction had a SECURITY verdict erased by the next
     * unrelated probe that happened to reach it. A trust verdict may only be cleared by a
     * trust act, so it gets a column no probe path writes.
     */
    public static final DateTimeField QUARANTINED_AT = SCHEMA.addField(
        DateTimeField.builder().name("quarantined_at").build());

    /** Why the host was quarantined, kept after {@link #LAST_ERROR} is overwritten. */
    public static final TextField QUARANTINE_REASON = SCHEMA.addField(
        TextField.builder().name("quarantine_reason").nullable(true).build());

    // -- the operator's posture acknowledgement (a trust ACT, like a pin) -------

    /**
     * The posture TOKEN an operator acknowledged the risk of, null when none. Never a
     * boolean: a flag would keep saying yes after the posture moved underneath it, and
     * the plan's clause refuses "a boolean hidden in settings" for exactly that shape.
     *
     * AIDEV-NOTE: COLUMNS are the authority and the activity row is HISTORY, the same
     * split {@code HostPins} uses. The activity log cannot be the authority here:
     * {@code CleanOldActivity} prunes at a hard-coded 90 days, so a gate reading it would
     * silently reopen the host on day 91 with nothing to see. And like
     * {@link #QUARANTINED_AT}, no probe, sweep or preflight path writes these five
     * columns -- a trust verdict may only be moved by a trust act.
     *
     * PROMOTION TRIGGER: the moment a SECOND consumer needs "a human accepted version N
     * of a stated risk about record R", lift this shape to zenit core
     * {@code common/security} as an {@code Acknowledgements} mechanism. One consumer is
     * not a mechanism -- the same call {@code HostTrustSlot} was deliberately given.
     */
    public static final StringField ACKNOWLEDGED_POSTURE = SCHEMA.addField(
        StringField.builder().name("acknowledged_posture").nullable(true).build());

    /** The {@link #POSTURE_WARNING_VERSION} the acknowledged warning text carried. */
    public static final IntegerField ACKNOWLEDGED_WARNING_VERSION = SCHEMA.addField(
        IntegerField.builder().name("acknowledged_warning_version").nullable(true).build());

    /** When the acknowledgement was recorded. */
    public static final DateTimeField ACKNOWLEDGED_AT = SCHEMA.addField(
        DateTimeField.builder().name("acknowledged_at").build());

    /** The acting principal id ({@code Accountability.current().actor()}), never a name. */
    public static final StringField ACKNOWLEDGED_BY = SCHEMA.addField(
        StringField.builder().name("acknowledged_by").nullable(true).build());

    /**
     * The actor's display label AS IT READ at acknowledgement time, so the record survives
     * a rename or a deleted account -- the accountability half is worthless if the only
     * human-readable trace is a foreign key that later resolves to nothing.
     */
    public static final StringField ACKNOWLEDGED_BY_LABEL = SCHEMA.addField(
        StringField.builder().name("acknowledged_by_label").nullable(true).build());

    /**
     * The operator-facing digest of {@link #HOST_KEY} ({@code SHA256:...}, exactly what
     * {@code ssh-keygen -lf} prints), derived at pin time and never entered by hand.
     *
     * AIDEV-NOTE: populated since the pinning wave (M057). An operator who cannot SEE a
     * fingerprint can never notice it changed, so this is displayed on the host form and
     * is the phrase the confirm/re-pin ceremonies make the operator type.
     *
     * AIDEV-NOTE: SSH ONLY since M074. This column and its four siblings used to be
     * shared with the Incus TLS ceremony, which meant one column was the authority for
     * two independent trust relationships -- it bit twice (the backup lane needed a
     * {@code MODE_SSH && !isIncus} guard so a certificate PEM could not reach the
     * known_hosts writer, and an Incus host could not hold an ssh admin lane at all
     * because its TLS material occupied the slot). The Incus side now lives in
     * {@link #INCUS_SERVER_CERT} and friends; see {@code HostTrustSlot}.
     */
    public static final StringField HOST_KEY_FINGERPRINT = SCHEMA.addField(
        StringField.builder().name("host_key_fingerprint").nullable(true).build());

    /**
     * The PINNED host public key in {@code known_hosts} spelling
     * ({@code ssh-ed25519 AAAA...}); every SSH connection verifies against exactly this
     * and fails closed on mismatch. Null means unpinned -- and an unpinned remote host
     * is not connectable at all.
     */
    public static final TextField HOST_KEY = SCHEMA.addField(
        TextField.builder().name("host_key").nullable(true).build());

    /**
     * Whether an operator confirmed {@link #HOST_KEY_FINGERPRINT} out of band. A scan
     * pins UNVERIFIED; only the explicit confirm action sets this, and nothing else may
     * -- an unverified pin silently becoming a verified one is the whole ceremony
     * defeating itself.
     */
    public static final BooleanField HOST_KEY_VERIFIED = SCHEMA.addField(
        BooleanField.builder("host_key_verified").defaultValue(false).build());

    /** When {@link #HOST_KEY} was pinned (or re-pinned). */
    public static final DateTimeField HOST_KEY_PINNED_AT = SCHEMA.addField(
        DateTimeField.builder().name("host_key_pinned_at").build());

    /**
     * The key a rescan most recently saw the host OFFER while it disagreed with
     * {@link #HOST_KEY}: evidence for the re-pin ceremony, never a value anything
     * connects against.
     */
    public static final TextField HOST_KEY_OFFERED = SCHEMA.addField(
        TextField.builder().name("host_key_offered").nullable(true).build());

    /** The per-host client public key an operator installs in the remote's authorized_keys. */
    public static final TextField IDENTITY_PUBLIC_KEY = SCHEMA.addField(
        TextField.builder().name("identity_public_key").nullable(true).build());

    /**
     * The per-host client PRIVATE key; encrypted at rest and never rendered. One key per
     * host, so a rotated or leaked credential is scoped to one machine instead of every
     * host the controller account can reach.
     *
     * AIDEV-NOTE: SSH ONLY since M074, like {@link #HOST_KEY} -- an Incus host's client
     * CERTIFICATE lives in {@link #INCUS_CLIENT_KEY}. The two cannot share a column
     * because an Incus host now legitimately holds both at once.
     */
    public static final TextField IDENTITY_PRIVATE_KEY = SCHEMA.addField(
        TextField.builder().name("identity_private_key").nullable(true)
            .secret().encrypted().build());

    // -- the Incus TLS trust relationship, INDEPENDENT of the ssh one above ----

    /**
     * The PINNED Incus server certificate (PEM) the https transport verifies against;
     * null means unpinned, and an unpinned https Incus host is not connectable at all.
     * The exact twin of {@link #HOST_KEY} for a different wire.
     */
    public static final TextField INCUS_SERVER_CERT = SCHEMA.addField(
        TextField.builder().name("incus_server_cert").nullable(true).build());

    /** The operator-facing digest of {@link #INCUS_SERVER_CERT} ({@code incus info}'s hex). */
    public static final StringField INCUS_SERVER_CERT_FINGERPRINT = SCHEMA.addField(
        StringField.builder().name("incus_server_cert_fingerprint").nullable(true).build());

    /** Whether an operator confirmed {@link #INCUS_SERVER_CERT_FINGERPRINT} out of band. */
    public static final BooleanField INCUS_SERVER_CERT_VERIFIED = SCHEMA.addField(
        BooleanField.builder("incus_server_cert_verified").defaultValue(false).build());

    /** When {@link #INCUS_SERVER_CERT} was pinned (or re-pinned). */
    public static final DateTimeField INCUS_SERVER_CERT_PINNED_AT = SCHEMA.addField(
        DateTimeField.builder().name("incus_server_cert_pinned_at").build());

    /** The certificate a rescan saw the daemon OFFER while it disagreed with the pin. */
    public static final TextField INCUS_SERVER_CERT_OFFERED = SCHEMA.addField(
        TextField.builder().name("incus_server_cert_offered").nullable(true).build());

    /** The per-host client CERTIFICATE an operator enrolls on the daemon's trust store. */
    public static final TextField INCUS_CLIENT_CERT = SCHEMA.addField(
        TextField.builder().name("incus_client_cert").nullable(true).build());

    /** The per-host client certificate's PRIVATE key; encrypted at rest, never rendered. */
    public static final TextField INCUS_CLIENT_KEY = SCHEMA.addField(
        TextField.builder().name("incus_client_key").nullable(true)
            .secret().encrypted().build());

    /** The controller version that last probed this host (compatibility-window bookkeeping). */
    public static final StringField CONTROLLER_VERSION = SCHEMA.addField(
        StringField.builder().name("controller_version").nullable(true).build());

    /**
     * The host's DECLARED public IPv4 literal -- THE server-address authority DNS A
     * generation reads (game-domain mappings). Null means the host declares no public
     * IPv4 and no A record is ever generated for workloads on it. Declared, never
     * probed: only the operator knows which of a host's addresses is the public one.
     */
    public static final StringField PUBLIC_IPV4 = SCHEMA.addField(
        StringField.builder().name("public_ipv4").nullable(true)
            .label(HohenheimFormCopy.label("public_ipv4"))
            .help(HohenheimFormCopy.help("public_ipv4"))
            .build());

    /** The host's declared public IPv6 literal (AAAA generation); null = none declared. */
    public static final StringField PUBLIC_IPV6 = SCHEMA.addField(
        StringField.builder().name("public_ipv6").nullable(true)
            .label(HohenheimFormCopy.label("public_ipv6"))
            .help(HohenheimFormCopy.help("public_ipv6"))
            .build());

    public static final DateTimeField CREATED_AT = SCHEMA.addField(DateTimeField.builder().name("created_at").build());
    public static final DateTimeField UPDATED_AT = SCHEMA.addField(DateTimeField.builder().name("updated_at").build());

    static {
        // The name is the human title (relation pickers, refusal messages), not "Server #id".
        SCHEMA.setDisplayFields(NAME);
        // Removing a host we can no longer observe must never delete its port claims (a
        // servers row vanishing frees nothing on the physical machine): they are parked
        // in "releasing" instead. Via the before/after pairing because a remove context
        // carries CRITERIA, not a row -- see PortLedger.captureDoomedOwners. There is no
        // FK delete action on port_allocations.server_id, so this hook IS the enforcement.
        //
        // AIDEV-NOTE: refuseRemovalWhileOwned runs FIRST and is THE enforcement of
        // "removal refuses while owned resources remain". The M051 FKs on
        // stacks/managed_databases.server_id carry no delete action, and SQLite only
        // enforces FKs per-connection via ?foreign_keys=on, which the production URL
        // does not set -- so without this hook the FK is documentation, and a host
        // delete silently orphans every stack, database and live instance on it.
        SCHEMA.addBeforeRemoveHook(ServerModel::refuseRemovalWhileOwned);
        SCHEMA.addBeforeRemoveHook(PortLedger::captureDoomedOwners);
        SCHEMA.addAfterRemoveHook(PortLedger::markDoomedServersReleasing);
        // A declared server address must be an IP LITERAL: DNS generation serves the
        // stored string verbatim as an A/AAAA value, so a hostname or garbage here would
        // materialize as a record nothing can resolve -- refused on EVERY write path.
        SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            String v4 = normalizeAddress(row, PUBLIC_IPV4);
            if (v4 != null && !IpLiterals.isIpv4(v4)) {
                throw Violations.ofField(PUBLIC_IPV4.getName(), v4,
                    Microcopy.of("server_address_invalid").withFilter("scope", "violations")
                        .withArg("address", v4));
            }
            String v6 = normalizeAddress(row, PUBLIC_IPV6);
            if (v6 != null && !IpLiterals.isIpv6(v6)) {
                throw Violations.ofField(PUBLIC_IPV6.getName(), v6,
                    Microcopy.of("server_address_invalid").withFilter("scope", "violations")
                        .withArg("address", v6));
            }
        });
        SCHEMA.addBeforeValidateHook(ServerModel::clearAcknowledgementOnPostureChange);
    }

    /**
     * An acknowledgement names ONE posture; the moment the row carries another, the
     * acknowledgement is not stale evidence to reason about, it is gone.
     *
     * AIDEV-NOTE: a SCHEMA HOOK, not a branch in the resource, and that is the whole
     * design. Posture reaches this row through two separate {@code ServerResource}
     * branches (the ordinary update and the local-host identity guard) plus whatever API
     * lands next -- a per-path clear is one forgotten path away from a host that quietly
     * keeps an acknowledgement it was never given for its current posture.
     *
     * It closes the AWAY-AND-BACK hole specifically. {@link #postureAcknowledged} already
     * refuses a mismatched pair on every read, so a stale row can never GRANT anything;
     * without this hook, though, flipping a host to dedicated and back to
     * shared_container would resurrect the old acknowledgement with no human involved.
     * Two layers on purpose: the predicate is the gate, this is the eraser.
     *
     * AIDEV-NOTE: both sides are read STAGED-ELSE-STORED, which is the whole point of the
     * fix on 2026-08-13. A partial update carries only the keys it changes, so a save that
     * stages posture ALONE -- the ordinary shape of "change the posture", and what every
     * CMS update produces -- arrived here with no acknowledged_posture on the row at all;
     * the hook read null, returned early, and the eraser silently did nothing on exactly
     * the write it exists for. The gate ({@link #postureAcknowledged}) still refused the
     * mismatched pair, so nothing was ever wrongly granted, but the away-and-back
     * resurrection this layer exists to prevent was fully open. Reading the stored row
     * costs one lookup on posture writes only: when POSTURE is not staged the posture
     * cannot have moved, so there is nothing to erase and nothing to load.
     */
    private static void clearAcknowledgementOnPostureChange(@NonNull SaveToDatasource context) {
        Row row = context.getRow();
        if (row == null || !row.has(POSTURE.getName())) {
            return;
        }
        Row stored = storedRowOf(row);
        String acknowledged = row.has(ACKNOWLEDGED_POSTURE.getName())
            ? row.get(ACKNOWLEDGED_POSTURE)
            : (stored != null ? stored.get(ACKNOWLEDGED_POSTURE) : null);
        if (acknowledged == null || acknowledged.equals(row.get(POSTURE))) {
            return;
        }
        row.set(ACKNOWLEDGED_POSTURE, null);
        row.set(ACKNOWLEDGED_WARNING_VERSION, null);
        row.set(ACKNOWLEDGED_AT, null);
        row.set(ACKNOWLEDGED_BY, null);
        row.set(ACKNOWLEDGED_BY_LABEL, null);
    }

    /** The persisted row behind a staged one, or null on a create (or an unreadable store). */
    private static @Nullable Row storedRowOf(@NonNull Row row) {
        if (!row.has(ID.getName())) {
            return null;
        }
        Object id = row.get(ID);
        return id == null ? null : Models.get(ServerModel.class).findById(id);
    }

    /** Trim a staged address; a blank submit folds to null (the "none declared" state). */
    private static @Nullable String normalizeAddress(@NonNull Row row,
                                                     @NonNull StringField field) {
        if (!row.has(field.getName())) {
            return null;
        }
        Object staged = row.get(field.getName());
        String value = staged != null ? String.valueOf(staged).trim() : "";
        row.set(field, value.isEmpty() ? null : value);
        return value.isEmpty() ? null : value;
    }

    /**
     * Refuse deleting any server that live stacks, managed databases or live (not
     * soft-deleted) instances still reference, or that an in-flight cold migration is
     * moving a workload ONTO; runs on EVERY delete path (service, admin resource,
     * criteria delete) because it is a schema hook.
     *
     * AIDEV-NOTE: the migration target is checked FIRST and by name. A workload mid-flight
     * is still attributed to its SOURCE host ({@code InstanceModel.SERVER_ID} stays the data
     * authority until the handoff), so the ownership count below never sees the
     * destination; without this branch the target could go while its daemon holds the
     * half-copied guest, and {@code InstanceMigrations.settle} would then resolve a host
     * that no longer exists.
     *
     * @throws Violations naming the server and what still owns it
     */
    static void refuseRemovalWhileOwned(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return;
        }
        var builder = model.find();
        var queryContext = context.getQueryContext();
        if (queryContext != null && queryContext.getCriteria() != null) {
            builder.where(queryContext.getCriteria());
        }
        for (Row doomed : builder.all()) {
            Integer serverId = doomed.get(ID);
            if (serverId == null) {
                continue;
            }
            Row migrating = migratingOnto(serverId).first();
            if (migrating != null) {
                throw Violations.ofForm(Microcopy.of("server_migration_target")
                    .withFilter("scope", "violations")
                    .withArg("name", String.valueOf((Object) doomed.get(NAME)))
                    .withArg("instance", String.valueOf((Object) migrating.get(InstanceModel.NAME))));
            }
            long stacks = Models.get(StackModel.class).find()
                .where(StackModel.SERVER_ID.eq(serverId)).count();
            long databases = Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.SERVER_ID.eq(serverId)).count();
            long instances = Models.get(InstanceModel.class).find()
                .where(InstanceModel.SERVER_ID.eq(serverId))
                .where(InstanceModel.DELETED_AT.isNull()).count();
            if (stacks > 0 || databases > 0 || instances > 0) {
                throw Violations.ofForm(Microcopy.of("server_in_use")
                    .withFilter("scope", "violations")
                    .withArg("name", String.valueOf((Object) doomed.get(NAME)))
                    .withArg("stacks", stacks)
                    .withArg("databases", databases)
                    .withArg("instances", instances));
            }
        }
    }

    /**
     * The live instances an open cold migration is moving onto this host -- THE one
     * query behind both the funnel refusal above and the admin resource's dead delete.
     */
    public static @NonNull QueryBuilder<Row> migratingOnto(int serverId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.MIGRATE_TARGET_ID.eq(serverId))
            .where(InstanceModel.DELETED_AT.isNull());
    }

    /** The server with this unique name, or null if none. */
    public Row findByName(String name) {
        return find().where(NAME.eq(name)).first();
    }

    // -- the runtime declaration ----------------------------------------------

    /** The host's declared runtime; a null column (pre-M070 row) folds to docker. */
    public static @NonNull String runtimeOf(@NonNull Row server) {
        String runtime = server.get(RUNTIME);
        return runtime == null || runtime.isBlank() ? RUNTIME_DOCKER : runtime;
    }

    /**
     * @return what this host's volume root can do; {@link VolumeBackend#NONE} when never
     *         probed or unrecognised, which is the fail-closed answer placement needs
     */
    public static @NonNull VolumeBackend volumeBackendOf(@NonNull Row server) {
        return VolumeBackend.resolve(server.get(VOLUME_BACKEND));
    }

    /** Whether this host declares the Incus runtime. */
    public static boolean isIncus(@NonNull Row server) {
        return RUNTIME_INCUS.equals(runtimeOf(server));
    }

    /** Whether this host's Incus daemon is reached over HTTPS (vs the local socket). */
    public static boolean isIncusHttps(@NonNull Row server) {
        String url = server.get(INCUS_URL);
        return isIncus(server) && url != null && url.trim().startsWith("https://");
    }

    /**
     * Whether this host declares an ssh ADMIN lane: a shell on the machine itself,
     * independent of what daemon it runs.
     *
     * AIDEV-NOTE: {@code ssh_target} is THE single authority for "there is a shell we can
     * reach", deliberately not {@link #MODE}. MODE is the DOCKER transport discriminator
     * ({@code local} vs {@code ssh}) and the host form stamps it from the RUNTIME, so an
     * Incus host is always {@code local} there and could never declare a shell through
     * it. For a docker host the two agree by construction: the form requires an
     * ssh_target for a docker host (mode ssh) and the reserved {@code local} row carries
     * none. This lane is what lets {@code IncusKernelIsolation} read the DAEMON HOST's
     * nftables for a host addressed over https.
     */
    public static boolean hasSshLane(@NonNull Row server) {
        String target = server.get(SSH_TARGET);
        return target != null && !target.isBlank();
    }

    /**
     * Whether this host's declared posture accepts TENANT workloads at all -- anything
     * other than {@code trusted_only}, including {@code dedicated} (one tenant owning the
     * whole machine is still a tenant).
     *
     * AIDEV-NOTE: THE single predicate behind every "does hostile code run here" gate, so
     * two gates cannot drift apart over the same question: placement refuses a
     * trusted_only host outright, and kernel-truth isolation verification is REQUIRED
     * exactly when this answers true. A null posture folds to trusted_only, the column's
     * own default.
     */
    public static boolean acceptsTenantWorkloads(@NonNull Row server) {
        String posture = server.get(POSTURE);
        return posture != null && !POSTURE_TRUSTED_ONLY.equals(posture);
    }

    /**
     * Whether this host's posture PROMISES a boundary the given workload cannot provide:
     * a {@code vm_isolated} host declares that hostile tenants only ever meet each other
     * across a hypervisor, and a shared-kernel workload breaks that promise for every
     * workload already there, not just for itself.
     *
     * AIDEV-NOTE: this is the pairing the plan's "placement refuses a hostile workload
     * when the host posture cannot satisfy it" asked for, and it lives HERE rather than in
     * the gate for the same reason {@link #acceptsTenantWorkloads} does: the posture
     * vocabulary is this model's, so what a posture permits is answered once. The other
     * three postures permit both isolations -- {@code trusted_only} refuses tenant
     * workloads outright one gate earlier (that is a TRUST decision, not a boundary one),
     * {@code dedicated} rations the host by owner, and {@code shared_container} is exactly
     * the posture whose shared kernel needs the operator acknowledgement.
     *
     * A vm_isolated host is deliberately NOT acknowledgeable: the honest way to run
     * containers on it is to declare shared_container and accept the risk, which is ONE
     * mechanism instead of two.
     */
    public static boolean postureRequiresVirtualMachine(@NonNull Row server,
                                                        @NonNull WorkloadIsolation isolation) {
        return POSTURE_VM_ISOLATED.equals(server.get(POSTURE))
            && isolation != WorkloadIsolation.VIRTUAL_MACHINE;
    }

    /**
     * Whether this host's posture is one an operator must explicitly accept the risk of
     * before hostile-tenant work lands on it -- {@code shared_container} and only that.
     */
    public static boolean postureNeedsAcknowledgement(@NonNull Row server) {
        return POSTURE_SHARED_CONTAINER.equals(server.get(POSTURE));
    }

    /**
     * Whether the stored acknowledgement answers for the posture this host declares TODAY
     * and for the CURRENT warning text. True when no acknowledgement is needed at all.
     *
     * Two independent invalidators, neither of which writes anything: a posture the
     * acknowledgement does not name (the schema hook normally erases those, so this is the
     * belt to its braces) and a {@link #POSTURE_WARNING_VERSION} bump.
     */
    public static boolean postureAcknowledged(@NonNull Row server) {
        if (!postureNeedsAcknowledgement(server)) {
            return true;
        }
        String acknowledged = server.get(ACKNOWLEDGED_POSTURE);
        Integer version = server.get(ACKNOWLEDGED_WARNING_VERSION);
        return acknowledged != null && acknowledged.equals(server.get(POSTURE))
            && version != null && version == POSTURE_WARNING_VERSION;
    }

    /**
     * Whether connecting to this host requires a pinned, operator-CONFIRMED identity:
     * every remote transport does (docker over ssh, incus over https), only the two
     * local-socket shapes do not. THE gate admission and placement key on -- never
     * re-derive from MODE alone, which says nothing about an Incus host.
     */
    public static boolean requiresPinnedIdentity(@NonNull Row server) {
        if (isIncus(server)) {
            return isIncusHttps(server);
        }
        return MODE_SSH.equals(server.get(MODE));
    }

    // -- THE canonical host-key derivation -----------------------------------

    /**
     * Fold any operator-facing host spelling to the canonical server NAME: null, blank
     * and {@code 0.0.0.0}-era empties become {@code local}, registry keys
     * ({@code hohenheim:<x>}) are unwrapped to their path, everything is trimmed.
     *
     * AIDEV-NOTE: THE one spelling normalisation. The local daemon used to be spelled
     * both {@code ""} and {@code "local"} across three separate normalisations
     * (DockerSiteRequestHandler, DatabaseService, StackServiceResource), which would
     * have split one machine's port claims into two disjoint sets while every unique
     * constraint held. Every consumer -- runtime resolution ({@link #canonicalServerId})
     * AND the M051 legacy heal -- must route through this method; never re-derive.
     */
    public static @NonNull String canonicalSpelling(@Nullable Object raw) {
        if (raw == null) {
            return MODE_LOCAL;
        }
        String spelling = String.valueOf(raw).trim();
        if (spelling.indexOf(':') >= 0) {
            Identifier key = Identifier.tryParse(spelling);
            if (key != null) {
                spelling = key.getPath().trim();
            }
        }
        return spelling.isEmpty() ? MODE_LOCAL : spelling;
    }

    /**
     * THE canonical host key: resolve any spelling (row id, {@code hohenheim:<id>}
     * registry key, server name, blank/"local") to the {@code servers.id} every
     * FK and port claim references.
     *
     * @throws IllegalArgumentException when the spelling names no known server
     */
    public static int canonicalServerId(@Nullable Object raw) {
        if (raw instanceof Number number) {
            return requireExisting(number.intValue());
        }
        String spelling = canonicalSpelling(raw);
        if (MODE_LOCAL.equals(spelling)) {
            return localServerId();
        }
        Row byName = Models.get(ServerModel.class).findByName(spelling);
        if (byName != null) {
            return byName.get(ID);
        }
        if (spelling.chars().allMatch(Character::isDigit)) {
            return requireExisting(Integer.parseInt(spelling));
        }
        throw new IllegalArgumentException("No server named '" + spelling + "'");
    }

    /** The implicit local daemon's row id, creating its row when absent (idempotent). */
    public static int localServerId() {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findByName(MODE_LOCAL);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(NAME, MODE_LOCAL);
            row.set(MODE, MODE_LOCAL);
            model.save(row);
        }
        return row.get(ID);
    }

    /** The display/transport name of a server id; a null id means the local daemon. */
    public static @NonNull String nameOf(@Nullable Integer serverId) {
        if (serverId == null) {
            Models.get(ServerModel.class);   // fail fast on an unbooted model registry
            return MODE_LOCAL;
        }
        Row row = Models.get(ServerModel.class).findById(serverId);
        if (row == null) {
            throw new IllegalArgumentException("No server with id " + serverId);
        }
        return String.valueOf(row.get(NAME));
    }

    /** The registry key a type-settings map stores for a server ({@code hohenheim:<id>}). */
    public static @NonNull String registryKeyOf(int serverId) {
        return Identifier.of("hohenheim", String.valueOf(serverId)).toString();
    }

    private static int requireExisting(int serverId) {
        if (Models.get(ServerModel.class).findById(serverId) == null) {
            throw new IllegalArgumentException("No server with id " + serverId);
        }
        return serverId;
    }

    @Override
    public Identifier getModelId() { return MODEL_ID; }

    @Override
    public Field<?, ?> getPrimaryKeyField() { return ID; }

    @Override
    public String getModelName() { return "Server"; }

    @Override
    public String getTableName() { return "servers"; }

    @Override
    public Schema getSchema() { return SCHEMA; }
}
