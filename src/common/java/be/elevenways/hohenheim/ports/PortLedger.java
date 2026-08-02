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
     * Claim one port exclusively.
     *
     * @throws PortConflict when another owner already holds the tuple
     */
    public static void claim(int serverId, @Nullable Object hostIp, int port,
                             @Nullable Object protocol, @Nullable Identifier ownerModel,
                             @Nullable Integer ownerId, @Nullable String note) {
        String key = claimKeyOf(serverId, hostIp, port, protocol);
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
        try {
            ledger.save(row);
        } catch (DuplicateKeyException conflict) {
            throw conflictFor(key, conflict);
        }
    }

    /** Release every claim held by one (model, record) owner. */
    public static void releaseOwner(@NonNull Identifier ownerModel, int ownerId) {
        Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.OWNER_MODEL.eq(ownerModel.toString()))
            .and(PortAllocationModel.OWNER_ID.eq(ownerId))
            .delete();
    }

    /** Every claim held by one (model, record) owner. */
    public static @NonNull List<Row> claimsOf(@NonNull Identifier ownerModel, int ownerId) {
        return Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.OWNER_MODEL.eq(ownerModel.toString()))
            .and(PortAllocationModel.OWNER_ID.eq(ownerId))
            .all();
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
            } else {
                Models.get(PortAllocationModel.class)
                    .delete(claim.get(PortAllocationModel.ID));
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
     * row is null -- so the doomed service ids are read here, before the rows disappear,
     * and consumed by {@link #releaseDoomedServices} on the SAME context instance.
     *
     * AIDEV-NOTE: this before/after pairing is the framework's documented seam
     * (RemoveFromDatasource's own docblock; zenit-auth's RecordGrantCleanup is the
     * reference consumer). An afterRemove-only hook reading {@code context.getRow()}
     * silently releases NOTHING -- the claims outlive their owner and the port stays
     * permanently unclaimable.
     */
    public static void captureDoomedServices(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return;
        }
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        QueryBuilder<Row> builder = model.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        List<Integer> doomed = new ArrayList<>();
        for (Row service : builder.all()) {
            Integer serviceId = service.get(StackServiceModel.ID);
            if (serviceId != null) {
                doomed.add(serviceId);
            }
        }
        if (!doomed.isEmpty()) {
            context.setAttribute(DOOMED_SERVICES, doomed);
        }
    }

    /** Release every claim held by the services the paired before-hook doomed. */
    public static void releaseDoomedServices(@NonNull RemoveFromDatasource context) {
        if (!(context.getAttribute(DOOMED_SERVICES) instanceof List<?> doomed) || doomed.isEmpty()) {
            return;
        }
        // ONE delete for the whole batch: deleting a stack dooms every one of its
        // services, and a per-service release would be a statement each.
        List<Integer> ids = new ArrayList<>();
        for (Object id : doomed) {
            if (id instanceof Integer serviceId) {
                ids.add(serviceId);
            }
        }
        if (!ids.isEmpty()) {
            Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.OWNER_MODEL.eq(StackServiceModel.MODEL_ID.toString()))
                .and(PortAllocationModel.OWNER_ID.in(ids))
                .delete();
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
