package be.elevenways.hohenheim.model;

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
     * AIDEV-NOTE: deliberately UNPOPULATED. SSH host-key pinning and enrollment
     * credential handling are a separate security wave (today enrollment is
     * StrictHostKeyChecking=accept-new with no pinning); the column exists so that wave
     * has a home, and nothing here reads or writes it yet.
     */
    public static final StringField HOST_KEY_FINGERPRINT = SCHEMA.addField(
        StringField.builder().name("host_key_fingerprint").nullable(true).build());

    /** The controller version that last probed this host (compatibility-window bookkeeping). */
    public static final StringField CONTROLLER_VERSION = SCHEMA.addField(
        StringField.builder().name("controller_version").nullable(true).build());

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
