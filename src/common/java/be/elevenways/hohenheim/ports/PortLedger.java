package be.elevenways.hohenheim.ports;

import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.DuplicateKeyException;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE single port authority: every persisted host-port claim goes through here, one row
 * per (server, bind address, port, protocol) in {@code port_allocations}, refused by the
 * UNIQUE claim-key index when contested (the RouteClaims shape: catch the
 * {@link DuplicateKeyException} and rethrow a NAMED conflict that can say who holds it).
 *
 * AIDEV-NOTE: concurrency posture mirrors RouteClaims -- hohenheim's SQLite engine
 * serializes write transactions (BEGIN IMMEDIATE), StackServiceModel/StackModel declare a
 * transaction around save + claim sync, so a friendly pre-write ledger READ cannot go
 * stale against a rival writer; the unique index stays as the storage-level backstop for
 * any writer that dodges the discipline. The OS probe (PortAllocator, C5) stays a
 * SEPARATE, later check: kernel-ephemeral consumers (IpcChannel, testcontainers) will
 * never have ledger rows, so the ledger can never replace the probe.
 */
public final class PortLedger {

    /** RouteClaims' separator convention: no part of a claim tuple can contain a newline. */
    private static final String SEPARATOR = "\n";

    /** Where the before-remove hook stashes the ids the after-remove hook must release. */
    private static final String DOOMED_SERVICES = "hohenheim.ports.doomed-services";

    private PortLedger() {
    }

    /**
     * A claim the ledger refused, naming the current holder -- the driver exception
     * names only the column, and a refusal that cannot say who owns the port is not a
     * refusal an operator can act on.
     */
    public static final class PortConflict extends RuntimeException {

        private final @NonNull String claimKey;
        private final @NonNull String holder;

        PortConflict(@NonNull String claimKey, @NonNull String holder, @Nullable Throwable cause) {
            super("Port " + claimKey.replace(SEPARATOR, " | ") + " is already claimed by " + holder,
                cause);
            this.claimKey = claimKey;
            this.holder = holder;
        }

        public @NonNull String getClaimKey() {
            return this.claimKey;
        }

        /** Human description of the owning record, for the operator-facing refusal. */
        public @NonNull String getHolder() {
            return this.holder;
        }
    }

    /**
     * The canonical per-host claim string, VERBATIM the spelling
     * StackServiceResource.portClaim established: trims both sides, folds a blank bind
     * address and {@code 0.0.0.0} into one whole-host bind, defaults {@code tcp}.
     */
    public static @NonNull String portClaim(@Nullable Object hostIp, int port,
                                            @Nullable Object protocol) {
        String address = trimmed(hostIp);
        if (address.equals("0.0.0.0")) {
            address = "";
        }
        // BlastString, never toLowerCase: this class is COMMON, and java.util.Locale is
        // banned there (its clinit would be anchored into the TeaVM bundle).
        String proto = BlastString.lower(trimmed(protocol));
        if (proto.isEmpty()) {
            proto = "tcp";
        }
        return address + "|" + port + "|" + proto;
    }

    /** THE claim key: the canonical host key plus the canonical per-host claim. */
    public static @NonNull String claimKeyOf(int serverId, @Nullable Object hostIp, int port,
                                             @Nullable Object protocol) {
        return serverId + SEPARATOR + portClaim(hostIp, port, protocol);
    }

    /** The ledger row currently holding a claim key, or null when the port is unclaimed. */
    public static @Nullable Row holderOf(@NonNull String claimKey) {
        return Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.CLAIM_KEY.eq(claimKey)).first();
    }

    /**
     * The row that would make this claim impossible AT THE KERNEL, not merely the row
     * with the same key: a whole-host bind and a specific-address bind of the same
     * (server, port, protocol) exclude each other, and only the unique index on the
     * exact key can see the first kind of clash.
     *
     * AIDEV-NOTE: this pre-write overlap read is REQUIRED, not a convenience. Until C4
     * every claim was whole-host (stacks declare {@code 0.0.0.0}), so key equality and
     * kernel exclusivity coincided; the two Docker cases publish on {@code 127.0.0.1},
     * so without this a docker site's 127.0.0.1:8080 and a stack's 0.0.0.0:8080 are two
     * happily co-existing rows for one impossible pair of binds -- a conflict check that
     * cannot catch the conflict it exists for. Two DIFFERENT specific addresses do not
     * exclude each other and are deliberately allowed. The unique index stays the
     * storage-level backstop for the exact-key race.
     */
    public static @Nullable Row conflictingHolder(int serverId, @Nullable Object hostIp, int port,
                                                  @Nullable Object protocol) {
        String key = claimKeyOf(serverId, hostIp, port, protocol);
        String address = canonicalAddressOf(key);
        for (Row claim : Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.SERVER_ID.eq(serverId))
                .and(PortAllocationModel.PORT.eq(port))
                .and(PortAllocationModel.PROTOCOL.eq(canonicalProtocolOf(key)))
                .all()) {
            String held = String.valueOf(claim.get(PortAllocationModel.HOST_IP));
            if (address.isEmpty() || held.isEmpty() || held.equals(address)) {
                return claim;
            }
        }
        return null;
    }

    /** Whether a ledger row is owned by this (model, record) tuple. */
    public static boolean isOwnedBy(@NonNull Row claim, @NonNull Identifier ownerModel,
                                    @Nullable Integer ownerId) {
        return ownerId != null
            && ownerModel.toString().equals(claim.get(PortAllocationModel.OWNER_MODEL))
            && ownerId.equals(claim.get(PortAllocationModel.OWNER_ID));
    }

    /**
     * Human description of a claim row's owner, resolved through the owning model's
     * display title so the refusal names something an operator recognizes.
     */
    public static @NonNull String describeHolder(@NonNull Row claim) {
        String ownerModel = claim.get(PortAllocationModel.OWNER_MODEL);
        Integer ownerId = claim.get(PortAllocationModel.OWNER_ID);
        if (ownerModel == null || ownerId == null) {
            String note = claim.get(PortAllocationModel.NOTE);
            return note != null && !note.isBlank() ? note : "an unrecorded owner";
        }
        Identifier modelId = Identifier.tryParse(ownerModel);
        Model model = modelId != null ? Models.get(modelId) : null;
        if (model == null) {
            return ownerModel + " #" + ownerId;
        }
        Row owner = model.findById(ownerId);
        if (owner == null) {
            return model.getModelName() + " #" + ownerId;
        }
        return model.getModelName() + " '" + model.getDisplayTitle(owner) + "'";
    }

    /**
     * Claim one port exclusively (state {@code held}).
     *
     * AIDEV-NOTE: a {@code releasing} row blocks a rival claim exactly like a held one --
     * a port that might still be bound is not available. Only the OWNER re-claiming a
     * tuple it already holds (typically one of its own releasing rows, e.g. a restored
     * site landing on the same ephemeral port) replaces its old row instead of
     * conflicting: one owner cannot contest itself over one kernel resource.
     *
     * @throws PortConflict when another owner already holds (or is still releasing) the tuple
     */
    public static void claim(int serverId, @Nullable Object hostIp, int port,
                             @Nullable Object protocol, @Nullable Identifier ownerModel,
                             @Nullable Integer ownerId, @Nullable String note) {
        claimFenced(serverId, hostIp, port, protocol, ownerModel, ownerId, note, null);
    }

    /**
     * {@link #claim} plus the writing controller's host-lease fence in
     * {@code controller_fence} -- the identity the boot sweep and the fence-guarded
     * release compare against for record-less managed-process claims.
     */
    public static void claimFenced(int serverId, @Nullable Object hostIp, int port,
                                   @Nullable Object protocol, @Nullable Identifier ownerModel,
                                   @Nullable Integer ownerId, @Nullable String note,
                                   @Nullable Long controllerFence) {
        claimCore(serverId, hostIp, port, protocol, ownerModel, ownerId, note,
            controllerFence, null);
    }

    /**
     * The DECLARED pre-allocation strategy of the same claim primitive: identical
     * refusal semantics, but the row is stamped {@code preallocated} -- the owner's
     * stable reservation, written BEFORE the workload exists and released only by a
     * verified destroy (a stop keeps it: the kernel port is free, the NUMBER is not).
     *
     * @throws PortConflict when another owner already holds (or is still releasing) the tuple
     */
    public static void claimPreallocated(int serverId, @Nullable Object hostIp, int port,
                                         @Nullable Object protocol,
                                         @NonNull Identifier ownerModel, int ownerId,
                                         @Nullable String note) {
        claimCore(serverId, hostIp, port, protocol, ownerModel, ownerId, note, null,
            PortAllocationModel.MODE_PREALLOCATED);
    }

    private static void claimCore(int serverId, @Nullable Object hostIp, int port,
                                  @Nullable Object protocol, @Nullable Identifier ownerModel,
                                  @Nullable Integer ownerId, @Nullable String note,
                                  @Nullable Long controllerFence, @Nullable String mode) {
        String key = claimKeyOf(serverId, hostIp, port, protocol);
        Row overlapping = conflictingHolder(serverId, hostIp, port, protocol);
        if (overlapping != null) {
            if (ownerModel == null || !isOwnedBy(overlapping, ownerModel, ownerId)) {
                throw new PortConflict(key, describeHolder(overlapping), null);
            }
            Models.get(PortAllocationModel.class)
                .delete(overlapping.get(PortAllocationModel.ID));
        }
        Model ledger = Models.get(PortAllocationModel.class);
        Row row = ledger.createEmptyRow();
        row.set(PortAllocationModel.SERVER_ID, serverId);
        row.set(PortAllocationModel.HOST_IP, canonicalAddressOf(key));
        row.set(PortAllocationModel.PORT, port);
        row.set(PortAllocationModel.PROTOCOL, canonicalProtocolOf(key));
        row.set(PortAllocationModel.CLAIM_KEY, key);
        row.set(PortAllocationModel.OWNER_MODEL, ownerModel != null ? ownerModel.toString() : null);
        row.set(PortAllocationModel.OWNER_ID, ownerId);
        row.set(PortAllocationModel.NOTE, note);
        row.set(PortAllocationModel.CONTROLLER_FENCE, controllerFence);
        row.set(PortAllocationModel.STATUS, PortAllocationModel.STATUS_HELD);
        row.set(PortAllocationModel.ALLOCATION_MODE, mode);
        try {
            ledger.save(row);
        } catch (DuplicateKeyException conflict) {
            throw conflictFor(key, conflict);
        }
    }

    /**
     * The UNVERIFIED release: park every claim of one (model, record) owner in
     * {@code releasing}. This is what a teardown that swallowed (or never attempted)
     * its Docker calls may do -- the rows keep blocking rival claims until an observer
     * ({@link #releaseObserved}, the reconciler sweep, or the boot sweep) sees the port
     * actually free and deletes them.
     */
    public static void releaseOwner(@NonNull Identifier ownerModel, int ownerId) {
        // Pre-allocated claims are deliberately NOT parked by a failed operation: the
        // reservation of the NUMBER is the point of the mode, a retry re-uses it, and a
        // parked row would be reaped by the reconciler the moment the (stopped) port
        // probes free -- losing the stable number DNS points at.
        List<Row> parking = new ArrayList<>();
        for (Row claim : claimsOf(ownerModel, ownerId)) {
            if (!isPreallocated(claim)) {
                parking.add(claim);
            }
        }
        markReleasing(parking);
    }

    /**
     * The VERIFIED release: delete every OBSERVED (record-after) claim of one owner
     * because the caller OBSERVED the teardown succeed (the daemon confirmed container
     * removal, or answered 404). Never call this from a path that swallowed the
     * teardown's failure. Pre-allocated claims survive -- a stop frees the kernel port
     * but never the reservation; {@link #releaseOwnerFully} is the destroy-path release.
     */
    public static void releaseOwnerObserved(@NonNull Identifier ownerModel, int ownerId) {
        Model ledger = Models.get(PortAllocationModel.class);
        for (Row claim : claimsOf(ownerModel, ownerId)) {
            if (!isPreallocated(claim)) {
                ledger.delete(claim.get(PortAllocationModel.ID));
            }
        }
    }

    /**
     * The verified END-OF-LIFE release: delete every claim of one owner, pre-allocated
     * reservations included. Only a path that OBSERVED the owner's workload gone for
     * good (a verified destroy) may call this.
     */
    public static void releaseOwnerFully(@NonNull Identifier ownerModel, int ownerId) {
        Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.OWNER_MODEL.eq(ownerModel.toString()))
            .and(PortAllocationModel.OWNER_ID.eq(ownerId))
            .delete();
    }

    /** Whether a claim row was written by the declared pre-allocation strategy. */
    public static boolean isPreallocated(@NonNull Row claim) {
        return PortAllocationModel.MODE_PREALLOCATED.equals(
            claim.get(PortAllocationModel.ALLOCATION_MODE));
    }

    /** Every pre-allocated claim of one owner, held or releasing. */
    public static @NonNull List<Row> preallocatedClaimsOf(@NonNull Identifier ownerModel,
                                                          int ownerId) {
        List<Row> result = new ArrayList<>();
        for (Row claim : claimsOf(ownerModel, ownerId)) {
            if (isPreallocated(claim)) {
                result.add(claim);
            }
        }
        return result;
    }

    /**
     * Park an owner's pre-allocated claims in {@code releasing}: the owner's declaration
     * no longer wants them (the publication changed shape), so the reservation ends the
     * observed way -- the reconciler deletes each row once it has seen the port free.
     */
    public static void parkPreallocatedClaims(@NonNull Identifier ownerModel, int ownerId) {
        markReleasing(preallocatedClaimsOf(ownerModel, ownerId));
    }

    /** Park one claim row in {@code releasing} (the selective form of the owner parks). */
    public static void parkClaim(@NonNull Row claim) {
        markReleasing(List.of(claim));
    }

    /** Delete one claim row after its port was observed free (the reconciler's authority). */
    public static void releaseObserved(@NonNull Row claim) {
        Models.get(PortAllocationModel.class).delete(claim.get(PortAllocationModel.ID));
    }

    /** Whether a claim row is parked in {@code releasing}. */
    public static boolean isReleasing(@NonNull Row claim) {
        return PortAllocationModel.STATUS_RELEASING.equals(claim.get(PortAllocationModel.STATUS));
    }

    /** Every {@code releasing} claim on one host. */
    public static @NonNull List<Row> releasingClaimsOf(int serverId) {
        return Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.SERVER_ID.eq(serverId))
            .and(PortAllocationModel.STATUS.eq(PortAllocationModel.STATUS_RELEASING))
            .all();
    }

    // Idempotent park: an already-releasing row is NOT re-saved, so its updated_at keeps
    // marking when releasing STARTED -- a crash-looping teardown retrying every start must
    // not reset the stuck-age clock the attention item runs on.
    private static void markReleasing(@NonNull List<Row> claims) {
        Model ledger = Models.get(PortAllocationModel.class);
        for (Row claim : claims) {
            if (!isReleasing(claim)) {
                claim.set(PortAllocationModel.STATUS, PortAllocationModel.STATUS_RELEASING);
                ledger.save(claim);
            }
        }
    }

    /** Every claim held by one (model, record) owner. */
    public static @NonNull List<Row> claimsOf(@NonNull Identifier ownerModel, int ownerId) {
        return Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.OWNER_MODEL.eq(ownerModel.toString()))
            .and(PortAllocationModel.OWNER_ID.eq(ownerId))
            .all();
    }

    /**
     * THE record-after primitive: write the claim for a port the KERNEL has already
     * handed out, replacing whatever this owner held before.
     *
     * AIDEV-NOTE: record-after is the decided default (instance-tier-plan, fork 2) --
     * pre-allocating does not remove the TOCTOU, it adds a second one seconds wide (an
     * image pull sits inside it) and, on a REMOTE host, is an unevidenced guess, because
     * isPortFree binds a LOCAL socket. Two consequences are deliberate. (1) A conflict is
     * REPORTED, never thrown: the container is already bound to this port, so a ledger row
     * that disagrees is the stale one, and failing the caller here would tear down a
     * working workload on the word of a row we know to be wrong. (2) The table lags reality
     * by the width of create -> start -> inspect; a controller death inside that window
     * leaves a port held by a container with NO row. That is survivable only because the
     * OwnerLabels land at container-CREATE, before the port exists, so DockerReconciler can
     * still attribute the container. Never move the labels after the readback.
     *
     * @return whether the claim was recorded; false means a stale/rival row holds the tuple
     */
    public static boolean recordObserved(int serverId, @Nullable Object hostIp, int port,
                                         @Nullable Object protocol, @NonNull Identifier ownerModel,
                                         int ownerId, @Nullable String note) {
        // The VERIFIED release is legitimate here and only here: both record-after flows
        // reach this point strictly after OwnerLabels.removeIfOwnedBy verified (via the
        // daemon) that the owner's previous container is gone, so its old port is an
        // observed-free fact, not an optimistic guess.
        releaseOwnerObserved(ownerModel, ownerId);
        try {
            claim(serverId, hostIp, port, protocol, ownerModel, ownerId, note);
            return true;
        } catch (PortConflict conflict) {
            Blast.log("PORTS: observed port", port, "of", ownerModel + " #" + ownerId,
                "could not be recorded -", conflict.getMessage(),
                "- the container holds the port regardless; the ledger row is stale");
            return false;
        }
    }

    /**
     * Delete one claim row by its key. An OBSERVED release: the only caller is the
     * managed-process teardown, which runs after the child process was reaped (and the
     * boot sweep's OS probe backstops the lingering-socket edge).
     *
     * @return the row that was removed, or null when nothing held the key
     */
    public static @Nullable Row releaseKey(@NonNull String claimKey) {
        Row held = holderOf(claimKey);
        if (held != null) {
            Models.get(PortAllocationModel.class).delete(held.get(PortAllocationModel.ID));
        }
        return held;
    }

    // -- stacks: the first wired consumer ------------------------------------

    /**
     * Diff-sync one stack service's DECLARED host ports against the ledger: stale claims
     * released, missing ones claimed, unchanged ones untouched. Runs from the model's
     * afterSave hook, inside the save's write transaction.
     *
     * @throws PortConflict when another owner holds a newly declared port
     */
    public static void syncStackService(int serviceId) {
        Row service = Models.get(StackServiceModel.class).findById(serviceId);
        if (service == null) {
            releaseOwner(StackServiceModel.MODEL_ID, serviceId);
            return;
        }
        Integer stackId = service.get(StackServiceModel.STACK_ID);
        Row stack = stackId != null ? Models.get(StackModel.class).findById(stackId) : null;
        Integer serverId = stack != null ? stack.get(StackModel.SERVER_ID) : null;
        int server = serverId != null ? serverId : ServerModel.localServerId();

        Map<String, Row> desired = new LinkedHashMap<>();
        for (Row port : service.getRecords(StackServiceModel.PORTS)) {
            Integer host = port.get(StackServiceModel.PORT_HOST);
            if (host == null) {
                continue;
            }
            desired.putIfAbsent(claimKeyOf(server, port.get(StackServiceModel.PORT_HOST_IP),
                host, port.get(StackServiceModel.PORT_PROTOCOL)), port);
        }

        Set<String> held = new HashSet<>();
        for (Row claim : claimsOf(StackServiceModel.MODEL_ID, serviceId)) {
            String key = claim.get(PortAllocationModel.CLAIM_KEY);
            if (desired.containsKey(key)) {
                held.add(key);
                if (isReleasing(claim)) {
                    // Re-declared while parked: the owner wants it again, so it is held.
                    claim.set(PortAllocationModel.STATUS, PortAllocationModel.STATUS_HELD);
                    Models.get(PortAllocationModel.class).save(claim);
                }
            } else {
                // An un-declared port is NOT observed free -- the stack may still run its
                // previous deploy, bound to it. Park it; an observer deletes it.
                markReleasing(List.of(claim));
            }
        }
        for (Map.Entry<String, Row> entry : desired.entrySet()) {
            if (held.contains(entry.getKey())) {
                continue;
            }
            Row port = entry.getValue();
            claim(server, port.get(StackServiceModel.PORT_HOST_IP),
                port.get(StackServiceModel.PORT_HOST),
                port.get(StackServiceModel.PORT_PROTOCOL),
                StackServiceModel.MODEL_ID, serviceId, null);
        }
    }

    /**
     * A delete carries only CRITERIA -- {@code Model.delete(id)}, a criteria delete and
     * {@code deleteAll} all fire the remove hooks ONCE with a criteria-only context whose
     * row is null -- so the doomed owners' primary keys are read here, before the rows
     * disappear, and consumed by {@link #releaseDoomedOwners} on the SAME context instance.
     *
     * AIDEV-NOTE: this before/after pairing is the framework's documented seam
     * (RemoveFromDatasource's own docblock; zenit-auth's RecordGrantCleanup is the
     * reference consumer). An afterRemove-only hook reading {@code context.getRow()}
     * silently releases NOTHING -- the claims outlive their owner and the port stays
     * permanently unclaimable. Model-agnostic on purpose: stack services and managed
     * databases are the same "the owning record is going away" case, and a second copy
     * of this pairing per model is how one of them silently stops releasing.
     */
    public static void captureDoomedOwners(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return;
        }
        String primaryKey = model.getPrimaryKeyField().getName();
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        QueryBuilder<Row> builder = model.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        List<Integer> doomed = new ArrayList<>();
        for (Row owner : builder.all()) {
            if (owner.get(primaryKey) instanceof Integer ownerId) {
                doomed.add(ownerId);
            }
        }
        if (!doomed.isEmpty()) {
            context.setAttribute(DOOMED_SERVICES, doomed);
        }
    }

    /**
     * Park every claim of the records the paired before-hook doomed in {@code releasing}.
     * A record delete is not an observation of the port being free -- the container (or
     * process) it described may outlive it -- so the rows survive their owner, still
     * blocking rival claims, until an observer deletes them. Verified teardown paths
     * (DatabaseService.destroy) call {@link #releaseOwnerObserved} BEFORE deleting the
     * record, so this hook only ever parks what no observer vouched for.
     */
    public static void releaseDoomedOwners(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null
            || !(context.getAttribute(DOOMED_SERVICES) instanceof List<?> doomed) || doomed.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        for (Object id : doomed) {
            if (id instanceof Integer ownerId) {
                ids.add(ownerId);
            }
        }
        if (!ids.isEmpty()) {
            markReleasing(Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.OWNER_MODEL.eq(model.getModelId().toString()))
                .and(PortAllocationModel.OWNER_ID.in(ids))
                .all());
        }
    }

    /**
     * Park every claim of the SERVERS the paired before-hook doomed ({@code servers} rows
     * share {@link #captureDoomedOwners}). A host we removed from the inventory is exactly
     * the host we can no longer observe, and a {@code servers} row vanishing does not free
     * ports on the physical machine -- so removal may never delete its claims.
     */
    public static void markDoomedServersReleasing(@NonNull RemoveFromDatasource context) {
        if (!(context.getAttribute(DOOMED_SERVICES) instanceof List<?> doomed) || doomed.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<>();
        for (Object id : doomed) {
            if (id instanceof Integer serverId) {
                ids.add(serverId);
            }
        }
        if (!ids.isEmpty()) {
            markReleasing(Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.SERVER_ID.in(ids))
                .all());
        }
    }

    /** Re-sync every service of a stack (the server-move path). */
    public static void syncStack(int stackId) {
        for (Row service : Models.get(StackServiceModel.class).findByStackId(stackId)) {
            syncStackService(service.get(StackServiceModel.ID));
        }
    }

    /**
     * Migration backfill: claim every stack service's declared host ports, lowest
     * service id winning a contested tuple and the losers left unclaimed (they were
     * already colliding at deploy time; their next edit is refused with the real
     * conflict message -- the M045 heal stance).
     *
     * @return the number of declared ports left unclaimed because an earlier service
     *         already held their tuple
     */
    public static int backfill(@NonNull Datasource datasource) {
        int[] released = {0};
        // Fresh instances, not Models.get: a migration may run long before the model
        // singletons are registered (the RouteClaims.backfill shape). Db.run scopes
        // every save to the migration's datasource.
        Db.run(datasource, () -> {
            Model stacks = new StackModel();
            StackServiceModel services = new StackServiceModel();
            Model ledger = new PortAllocationModel();
            Map<Integer, Integer> serverByStack = new HashMap<>();
            for (Row stack : stacks.find().all()) {
                serverByStack.put(stack.get(StackModel.ID), stack.get(StackModel.SERVER_ID));
            }
            Set<String> claimed = new HashSet<>();
            for (Row service : services.find()
                    .orderBy(StackServiceModel.ID, SortOrder.ASC).all()) {
                Integer serverId = serverByStack.get(service.get(StackServiceModel.STACK_ID));
                if (serverId == null) {
                    continue;   // orphaned service row: no host, nothing claimable
                }
                for (Row port : service.getRecords(StackServiceModel.PORTS)) {
                    Integer host = port.get(StackServiceModel.PORT_HOST);
                    if (host == null) {
                        continue;
                    }
                    String key = claimKeyOf(serverId, port.get(StackServiceModel.PORT_HOST_IP),
                        host, port.get(StackServiceModel.PORT_PROTOCOL));
                    if (!claimed.add(key)) {
                        released[0]++;
                        continue;
                    }
                    Row row = ledger.createEmptyRow();
                    row.set(PortAllocationModel.SERVER_ID, serverId);
                    row.set(PortAllocationModel.HOST_IP, canonicalAddressOf(key));
                    row.set(PortAllocationModel.PORT, host);
                    row.set(PortAllocationModel.PROTOCOL, canonicalProtocolOf(key));
                    row.set(PortAllocationModel.CLAIM_KEY, key);
                    row.set(PortAllocationModel.OWNER_MODEL, StackServiceModel.MODEL_ID.toString());
                    row.set(PortAllocationModel.OWNER_ID, service.get(StackServiceModel.ID));
                    // No STATUS here: this runs inside M051, BEFORE M052 adds the column;
                    // M052's heal stamps these rows "held".
                    ledger.save(row);
                }
            }
        });
        if (released[0] > 0) {
            Blast.log("PORTS: backfill left", released[0],
                "contested stack port declaration(s) unclaimed (lowest service id kept each)");
        }
        return released[0];
    }

    private static @NonNull PortConflict conflictFor(@NonNull String key,
                                                     @NonNull Throwable cause) {
        Row holder = holderOf(key);
        String description = holder != null ? describeHolder(holder) : "an unknown owner";
        return new PortConflict(key, description, cause);
    }

    private static @NonNull String canonicalAddressOf(@NonNull String claimKey) {
        String claim = claimKey.substring(claimKey.indexOf(SEPARATOR) + 1);
        return claim.substring(0, claim.indexOf('|'));
    }

    private static @NonNull String canonicalProtocolOf(@NonNull String claimKey) {
        return claimKey.substring(claimKey.lastIndexOf('|') + 1);
    }

    private static @NonNull String trimmed(@Nullable Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
