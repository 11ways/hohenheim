package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.net.IpLiterals;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.field.*;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
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
        .value(MODE_LOCAL, v -> v.displayName("Local").icon("house").color("teal"))
        .value(MODE_SSH, v -> v.displayName("SSH").icon("terminal").color("indigo"))
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
     * The host's declared isolation posture. Data the allocator reads, never operator
     * memory.
     *
     * AIDEV-NOTE: the shared_container acknowledgement record (actor, timestamp,
     * warning version -- the plan's requirement) is NOT built yet; until it is, setting
     * shared_container is a bare admin edit. Follow-up owed with the placement
     * allocator.
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
        .build());

    /**
     * Whether this host accepts placement. Defaults to {@code blocked}: a host with no
     * successful preflight is never silently trusted, including hosts enrolled before
     * preflight existed. Transitions are operator ACTIONS (admit/cordon), never form
     * fields.
     *
     * AIDEV-NOTE: no {@code draining} token yet -- drain needs the durable
     * transfer/stop policy the plan requires and no transfer mechanism exists; a state
     * that promises emptying while nothing empties would be the reports-success shape.
     * Cordon (refuse new placement) is the honest subset that exists today.
     */
    public static final EnumField ADMISSION = SCHEMA.addField(EnumField.builder("admission")
        .value(ADMISSION_BLOCKED, v -> v.displayName("Blocked").icon("circle-xmark")
            .label(Microcopy.of("blocked").withFilter("scope", "host_admission")).color("red"))
        .value(ADMISSION_ADMITTED, v -> v.displayName("Admitted").icon("circle-check")
            .label(Microcopy.of("admitted").withFilter("scope", "host_admission")).color("green"))
        .value(ADMISSION_CORDONED, v -> v.displayName("Cordoned").icon("circle-pause")
            .label(Microcopy.of("cordoned").withFilter("scope", "host_admission")).color("orange"))
        .defaultValue(ADMISSION_BLOCKED)
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

    /** Last time any probe (preflight, live overview, reconcile sweep) reached the daemon. */
    public static final DateTimeField LAST_SEEN_AT = SCHEMA.addField(
        DateTimeField.builder().name("last_seen_at").build());

    /** Typed failure class of the last failed probe (HostProbe.FailureKind token), null when healthy. */
    public static final StringField LAST_ERROR_KIND = SCHEMA.addField(
        StringField.builder().name("last_error_kind").nullable(true).build());

    /** Human detail of the last failed probe, null when healthy. */
    public static final TextField LAST_ERROR = SCHEMA.addField(
        TextField.builder().name("last_error").nullable(true).build());

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
     * soft-deleted) instances still reference; runs on EVERY delete path (service,
     * admin resource, criteria delete) because it is a schema hook.
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
