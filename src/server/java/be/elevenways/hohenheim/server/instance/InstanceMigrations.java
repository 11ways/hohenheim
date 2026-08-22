package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.game.GameDomains;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.VolumeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.WorkloadAttribution;
import be.elevenways.hohenheim.server.runtime.WorkloadAttribution.WorkloadClaim;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Consumer;

/**
 * COLD migration of one instance between hosts, and the host DRAIN built on it.
 *
 * AIDEV-NOTE: THE migration-policy decision (2026-08-06, Phase 8 gate). Cold --
 * stop, whole-instance export, import on the destination, start -- is the chosen
 * policy; live migration is REJECTED for now (recorded in the plan's Proxmox-use
 * inventory): incus stateful transfer needs migration.stateful set before start,
 * CRIU for containers and matched CPU flags for VMs, plus a daemon-to-daemon trust
 * relationship this product deliberately does not hold. The TRANSPORT is
 * controller-mediated either way (daemon A -> controller staging -> daemon B) and
 * comes in two shapes behind ONE orchestration (see {@link Transport}): the native
 * whole-instance export/import pair (Incus -- carries re-attribution, the MAC strip,
 * the isolation rejoin and the pool-resident snapshots), and the volume transport
 * (Docker, 2026-08-12 -- cold capture of the DECLARED logical volumes, recreate from
 * the spec on the destination, restore into the freshly minted volumes; daemon-side
 * state that is not a declared volume does not travel, exactly like a backup of the
 * same kind). Incus's own cross-host copy stays rejected: it would be a second
 * transfer path riding a daemon-to-daemon trust relationship that exists nowhere
 * else in the product.
 *
 * AIDEV-NOTE: ownership discipline. The record's server_id is THE single pointer,
 * and every step keeps it truthful: the source host stays the data authority until
 * the source copy is verified gone, then ONE guarded statement (the handoff) repoints
 * the record and re-bases the fence into the destination's lease domain. Every crash
 * window settles deterministically from (server_id, migrate_target_id) plus daemon
 * attribution -- see {@link #settle}. The source is stopped for the whole window
 * (STATUS_MIGRATING refuses deploy/stop), so the two copies can never diverge and a
 * rollback loses nothing.
 */
public final class InstanceMigrations {

    private static final DateTimeFormatter STAMP = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** The activity action a completed cold migration is recorded under. */
    public static final String ACTIVITY_MIGRATE_ACTION = "migrated";

    /** The activity action a host drain is recorded under, on the SERVER record. */
    public static final String ACTIVITY_DRAIN_ACTION = "drained";

    private final @NonNull InstanceService instances;

    /**
     * Test seam: invoked with a step name after each daemon-side milestone
     * ("stopped", "exported", "imported", "source_removed", "flipped"). A hook that
     * throws an unchecked exception simulates a controller killed at that point --
     * it escapes the IOException net, so no in-process settle runs and the record
     * is left exactly as a dead controller would leave it.
     */
    private final @NonNull Consumer<String> checkpoint;

    /** Destination-headroom gate; the production one is {@link RestoreCapacity#require}. */
    public interface CapacityCheck {
        void require(int serverId, long requiredBytes);
    }

    private final @NonNull CapacityCheck capacity;

    public InstanceMigrations() {
        this(new InstanceService(), step -> {});
    }

    public InstanceMigrations(@NonNull InstanceService instances,
                              @NonNull Consumer<String> checkpoint) {
        this(instances, checkpoint, RestoreCapacity::require);
    }

    /** Test seam: daemon-free tests replace the capacity probe, nothing else. */
    public InstanceMigrations(@NonNull InstanceService instances,
                              @NonNull Consumer<String> checkpoint,
                              @NonNull CapacityCheck capacity) {
        this.instances = instances;
        this.checkpoint = checkpoint;
        this.capacity = capacity;
    }

    // -- destination survey (the operator surface) ------------------------------

    /**
     * One candidate host as the migrate surface renders it.
     *
     * @param eligible whether every pre-flight this survey can ask WITHOUT touching a
     *                 daemon passed; {@link #migrateTo} remains the gate
     * @param refusal  the named reason an ineligible host was excluded, else null
     */
    public record Destination(int serverId, @NonNull String name, boolean eligible,
                              @Nullable Microcopy refusal, int bookedMb, int bookableMb) {}

    /**
     * Every host this instance could be told to move to, each either eligible or carrying
     * the NAMED reason it is not.
     *
     * AIDEV-NOTE: an ADVISORY projection, deliberately CALLING the same gates rather than
     * restating them -- the InstancePlacement lesson one file over (a re-stated eligible set
     * silently drifts from the authority it imitates). It skips exactly the checks that
     * touch a daemon or move capacity (the destination workload claim, the memory window),
     * so a host listed eligible here can still be refused BY NAME at submit; that is the
     * documented split, never a success toast over a refusal.
     *
     * @throws Violations when the instance itself cannot be resolved
     */
    public @NonNull List<Destination> destinationsFor(int instanceId) {
        Resolved resolved = this.instances.resolve(instanceId);
        Set<String> supportedRuntimes = resolved.handler().supportedRuntimes();
        boolean sourceTransportable = hasTransport(resolved.runtime());
        List<Destination> destinations = new ArrayList<>();
        for (Row server : Models.get(ServerModel.class).find().all()) {
            Integer serverId = server.get(ServerModel.ID);
            if (serverId == null || serverId == resolved.serverId()) {
                continue;
            }
            String name = String.valueOf((Object) server.get(ServerModel.NAME));
            Microcopy refusal = refusalFor(server, serverId, name, resolved,
                supportedRuntimes, sourceTransportable);
            Long budget = InstanceCapacity.budgetMbOf(server);
            destinations.add(new Destination(serverId, name, refusal == null, refusal,
                clampInt(InstanceCapacity.bookedMbOn(serverId)),
                budget == null ? 0
                    : clampInt(InstanceCapacity.bookableMbOn(serverId, budget))));
        }
        return destinations;
    }

    /**
     * The first named reason this host cannot receive the workload, or null.
     *
     * The instance-scoped gates (devices, game pairing, publication) are re-asked here
     * even though their answer is the same for every host, because the survey's contract
     * is a NAMED reason beside every ineligible destination -- an empty eligible list
     * with no reason is exactly the illegible surface the Incus-only refusal never was.
     */
    private static @Nullable Microcopy refusalFor(@NonNull Row server, int serverId,
                                                  @NonNull String name,
                                                  @NonNull Resolved resolved,
                                                  @NonNull Set<String> supportedRuntimes,
                                                  boolean sourceTransportable) {
        Microcopy runtimeRefusal = InstanceKinds.runtimeMismatch(name,
            ServerModel.runtimeOf(server), supportedRuntimes);
        if (runtimeRefusal != null) {
            return runtimeRefusal;
        }
        // Through the ONE named-refusal funnel (InstanceService.runtimeFor): client
        // construction fails closed for an unpinned SSH host, and letting that throw
        // escape here 500ed the whole page over ONE unaddressable host in the estate
        // (found by the full suite 2026-08-12 over another class's fixture host).
        InstanceRuntime targetRuntime;
        try {
            targetRuntime = InstanceService.runtimeFor(resolved.handler(), name);
        } catch (Violations unaddressable) {
            List<Violation> named = unaddressable.all();
            return named.isEmpty()
                ? violationText("instance_host_unreachable").withArg("name", name)
                    .withArg("reason", "client construction failed")
                : named.get(0).message();
        }
        if (!sourceTransportable || !hasTransport(targetRuntime)) {
            return violationText("migrate_unsupported")
                .withArg("name", nameOf(resolved.row()));
        }
        long devices = deviceCountOf(resolved.row().get(InstanceModel.ID));
        if (devices > 0) {
            return violationText("migrate_devices_present")
                .withArg("name", nameOf(resolved.row()))
                .withArg("count", devices);
        }
        if (GameDomains.isPaired(resolved.row().get(InstanceModel.ID))) {
            return violationText("migrate_game_paired")
                .withArg("name", nameOf(resolved.row()));
        }
        if (resolved.spec().publication() != null) {
            return violationText("migrate_publication_present")
                .withArg("name", nameOf(resolved.row()));
        }
        return HostAdmission.instancePlacementRefusal(serverId, resolved.handler().isolation(),
            resolved.row().get(InstanceModel.QUOTA_BUCKET));
    }

    private static int clampInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    // -- single-instance migration ---------------------------------------------

    /**
     * Migrate to a destination the placement chooser picks (admitted, posture-
     * accepting, same runtime, never the current host) -- the drain lane's shape.
     *
     * @return the destination host id
     * @throws Violations naming the refusal or failure
     */
    public int migrate(int instanceId) {
        Resolved resolved = this.instances.resolve(instanceId);
        String bucket = resolved.row().get(InstanceModel.QUOTA_BUCKET);
        int target = InstancePlacement.chooseForBucket(bucket == null ? "" : bucket,
            InstancePlacement.Workload.of(resolved.handler(), resolved.row()),
            resolved.serverId());
        migrateTo(instanceId, target);
        return target;
    }

    /**
     * Cold-migrate one instance to the named host: stop on the source, export the
     * whole instance (snapshots included), import on the destination, verify, remove
     * the source copy, hand the record over, and start it again if it was running.
     *
     * @throws Violations naming the refusal or failure
     */
    public void migrateTo(int instanceId, int targetServerId) {
        // Operator-only for the same reason restore-to-new is: this lane bypasses the
        // tenant creation funnel and decides placement -- both operator authorities.
        if (TenantWrites.isTenantOriginated()) {
            throw Violations.ofForm(violationText("migrate_operator_only"));
        }
        Resolved resolved = this.instances.resolve(instanceId);
        InstanceOperationGuard.requireOperable(resolved.row());
        if (InstanceModel.INSTALL_INSTALLING.equals(
                resolved.row().get(InstanceModel.INSTALL_STATE))) {
            throw Violations.ofForm(violationText("instance_busy")
                .withArg("name", nameOf(resolved.row()))
                .withArg("status", InstanceModel.INSTALL_INSTALLING));
        }
        if (targetServerId == resolved.serverId()) {
            throw Violations.ofForm(violationText("migrate_same_host")
                .withArg("name", nameOf(resolved.row())));
        }
        Row target = Models.get(ServerModel.class).findById(targetServerId);
        InstanceKinds.requireRuntimeMatch(ServerModel.nameOf(targetServerId),
            target != null ? ServerModel.runtimeOf(target) : "absent",
            resolved.handler().supportedRuntimes());
        HostAdmission.requireInstancePlacement(targetServerId, resolved.handler().isolation(),
            resolved.row().get(InstanceModel.QUOTA_BUCKET));

        String targetName = ServerModel.nameOf(targetServerId);
        // The named-refusal funnel: an unaddressable destination (unpinned SSH host)
        // answers instance_host_unreachable, never a raw HostTrustException 500.
        InstanceRuntime targetRuntime = InstanceService.runtimeFor(resolved.handler(),
            targetName);
        Transport transport = transportFor(resolved, targetRuntime);
        if (transport == null) {
            throw Violations.ofForm(violationText("migrate_unsupported")
                .withArg("name", nameOf(resolved.row())));
        }
        // Device rows are UNMOVABLE this wave, refused by name: neither transport
        // carries custom volumes (the native export skips them, the volume capture
        // reads only the DECLARED logical volumes), and the destination deploy's device
        // reconcile would attach FRESH EMPTY volumes -- a migration that "succeeds"
        // while silently emptying a tenant's disk is exactly the silent-success shape
        // this refusal exists to kill. Extra NICs are refused with them: their bridge
        // is host-local and an import naming it can fail or dangle.
        long devices = deviceCountOf(instanceId);
        if (devices > 0) {
            throw Violations.ofForm(violationText("migrate_devices_present")
                .withArg("name", nameOf(resolved.row()))
                .withArg("count", devices));
        }
        // A game-domain mapping binds this instance to its proxy/backend PARTNER over a
        // host-local link network (GameDomains refuses a cross-host mapping by the same
        // name), so moving either side alone would violate the exact invariant the
        // create path refuses. Refused BY NAME until a pair-coherent move exists;
        // severing the mapping first is the operator's explicit decision, never this
        // lane's silent side effect.
        if (GameDomains.isPaired(instanceId)) {
            throw Violations.ofForm(violationText("migrate_game_paired")
                .withArg("name", nameOf(resolved.row())));
        }
        // A port publication is a host-scoped reservation (DNS may point at it); moving
        // one silently would strand the claim -- refused by name until a consumer needs it.
        // AIDEV-NOTE: since the volume transport (2026-08-12) this gate is REACHABLE:
        // docker kinds are migratable and several publish. The Velocity game proxy is
        // refused one gate earlier as game-paired when it carries mappings, and lands
        // HERE when it does not -- its public port claim is what cannot move.
        if (resolved.spec().publication() != null) {
            throw Violations.ofForm(violationText("migrate_publication_present")
                .withArg("name", nameOf(resolved.row())));
        }

        String handle = resolved.spec().handle();
        boolean wasRunning = resolved.runtime().status(handle).running();
        long sourceFence = this.instances.leases().requireFence(resolved.serverId());
        long targetFence = this.instances.leases().requireFence(targetServerId);

        // The destination pre-flight: a FOREIGN same-named workload is the handle-
        // collision hazard and refuses the whole migration; an OURS leftover is a
        // previous attempt's debris (the record still points at the source, which
        // holds the authoritative data) and is removed before the fresh import.
        // AIDEV-NOTE: only the volume transport DEMANDS attribution on both ends; the
        // native one does not, so a native-only destination has no claim to answer and
        // simply skips the pre-flight (the same reading `settle` takes -- an
        // unattributed target counts as ABSENT there).
        if (targetRuntime instanceof WorkloadAttribution targetAttribution) {
            try {
                WorkloadClaim claim = targetAttribution.claimOf(resolved.spec());
                if (claim == WorkloadClaim.FOREIGN) {
                    throw Violations.ofForm(violationText("migrate_destination_occupied")
                        .withArg("name", nameOf(resolved.row()))
                        .withArg("server", targetName));
                }
                if (claim == WorkloadClaim.OURS) {
                    removeMigrationCopy(targetRuntime, resolved);
                }
            } catch (IOException unreachable) {
                throw refusal("instance_migrate_failed", resolved.row(), unreachable);
            }
        }

        // The destination's MEMORY headroom, and the last refusal before anything moves:
        // migrateTo names its own host, so nothing else on this lane asks whether the
        // workload fits there (the chooser does it for the drain lane, RestoreCapacity
        // answers for DISK only). The booking is held for the whole window and settled by
        // the fenced write that ends it -- see InstanceCapacity.openMigrationWindow.
        long reservedMb = InstanceCapacity.openMigrationWindow(instanceId, targetServerId);
        try {
            InstanceOperationGuard.stampMigrating(this.instances.leases(), instanceId,
                resolved.serverId(), sourceFence, targetServerId, reservedMb,
                nameOf(resolved.row()));
        } catch (RuntimeException notOurs) {
            // The window never opened, so no settle will ever close it. The EXACT
            // reservation comes back, never a recompute of the row's current booking.
            InstanceCapacity.release(targetServerId, reservedMb);
            throw notOurs;
        }
        Path staging = stagingRoot().resolve("migrate-" + instanceId + "-"
            + STAMP.format(Instant.now()));
        try {
            InstanceConsoles.markStopExpected(instanceId);
            InstanceConsoles.closeSession(instanceId);
            resolved.runtime().stop(handle, 10);
            PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
            this.checkpoint.accept("stopped");

            Files.createDirectories(staging);
            long size = transport.exportFromSource(staging);
            this.checkpoint.accept("exported");

            this.capacity.require(targetServerId, size);
            transport.importOnTarget();
            this.checkpoint.accept("imported");
            // "The API said yes" and "the destination holds a workload" are
            // independent facts; a move that leaves nothing on the far side must
            // never report success.
            if (targetRuntime.status(handle).state() == ContainerState.ABSENT) {
                throw new IOException("Import of '" + handle + "' on '" + targetName
                    + "' was accepted but the destination daemon holds no such workload");
            }

            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, instanceId);
            resolved.runtime().destroy(handle);
            if (resolved.runtime().status(handle).state() != ContainerState.ABSENT) {
                throw new IOException("Source copy of '" + handle + "' still exists on '"
                    + ServerModel.nameOf(resolved.serverId())
                    + "' after its removal was accepted");
            }
            // What the source daemon holds BESIDE the container (the volume transport's
            // named volumes) goes with it: tenant data left on the old host, and a later
            // migration BACK would merge-restore over the stale copy.
            transport.removeSourceRemnants();
            this.checkpoint.accept("source_removed");

            InstanceOperationGuard.handoff(this.instances.leases(), instanceId,
                resolved.serverId(), sourceFence, targetServerId, targetFence,
                InstanceModel.STATUS_STOPPED, nameOf(resolved.row()));
            this.checkpoint.accept("flipped");
        } catch (IOException error) {
            // The same deterministic settle a killed controller gets at boot; it
            // decides rollback vs forward-completion from daemon truth, so a failure
            // after the source copy is gone still lands on the destination.
            boolean settled = settleQuietly(instanceId);
            if (settled && wasRunning) {
                Row row = Models.get(InstanceModel.class).findById(instanceId);
                if (row != null && InstanceModel.STATUS_STOPPED
                        .equals(row.get(InstanceModel.STATUS))
                        && resolved.serverId() == ServerModel.canonicalServerId(
                            row.get(InstanceModel.SERVER_ID))) {
                    redeployBestEffort(instanceId);
                }
            }
            throw refusal("instance_migrate_failed", resolved.row(), error);
        } finally {
            InstanceSnapshots.deleteRecursively(staging);
        }

        Blast.log("MIGRATE: moved", handle, "from",
            ServerModel.nameOf(resolved.serverId()), "to", targetName);
        recordMigration(instanceId, ServerModel.nameOf(resolved.serverId()), targetName);
        if (wasRunning) {
            TenantWrites.inAuthorizedOperation(() -> this.instances.deploy(instanceId));
        }
    }

    // -- drain -------------------------------------------------------------------

    /** One drained-or-refused workload in a {@link DrainReport}. */
    public record DrainEntry(int instanceId, @NonNull String name, @NonNull String detail) {}

    /** The per-instance outcome of one drain pass; {@code complete} = host holds none. */
    public record DrainReport(@NonNull List<DrainEntry> moved,
                              @NonNull List<DrainEntry> refused,
                              boolean complete) {}

    /**
     * Drain a CORDONED host: migrate every live instance elsewhere; a workload that
     * cannot move is REFUSED BY NAME and left exactly as it was -- drain is operator
     * convenience, never authority to stop or destroy a tenant's workload, so an
     * incomplete drain ends loudly incomplete with the host still cordoned.
     *
     * @throws Violations {@code drain_requires_cordon} when the host is not cordoned
     */
    public @NonNull DrainReport drain(int serverId) {
        if (TenantWrites.isTenantOriginated()) {
            throw Violations.ofForm(violationText("migrate_operator_only"));
        }
        Row server = Models.get(ServerModel.class).findById(serverId);
        if (server == null || !ServerModel.ADMISSION_CORDONED
                .equals(server.get(ServerModel.ADMISSION))) {
            throw Violations.ofForm(violationText("drain_requires_cordon")
                .withArg("name", server != null
                    ? String.valueOf((Object) server.get(ServerModel.NAME))
                    : String.valueOf(serverId)));
        }
        List<DrainEntry> moved = new ArrayList<>();
        List<DrainEntry> refused = new ArrayList<>();
        for (Row instance : instancesOn(serverId)) {
            int id = instance.get(InstanceModel.ID);
            String name = String.valueOf((Object) instance.get(InstanceModel.NAME));
            try {
                int target = migrate(id);
                moved.add(new DrainEntry(id, name, ServerModel.nameOf(target)));
            } catch (Violations refusal) {
                refused.add(new DrainEntry(id, name, refusal.getMessage()));
            }
        }
        boolean complete = instancesOn(serverId).isEmpty();
        Blast.log("DRAIN:", ServerModel.nameOf(serverId), "moved", moved.size(),
            "refused", refused.size(), complete ? "- host holds none" : "- INCOMPLETE");
        // The drain itself is an operator act on the HOST, distinct from the per-instance
        // rows recordMigration wrote: an incomplete drain must be as answerable as a
        // complete one, so this is recorded on both outcomes.
        ActivityLog.record(Models.get(ServerModel.class), serverId, ACTIVITY_DRAIN_ACTION,
            "moved " + moved.size() + ", refused " + refused.size()
                + (complete ? ", host holds none" : ", INCOMPLETE"));
        return new DrainReport(moved, refused, complete);
    }

    /**
     * Record the completed move on the INSTANCE record.
     *
     * AIDEV-NOTE: explicit, and in the authority rather than in the CMS row action, for
     * the same reason {@link InstanceSnapshots#recordRestore} is: a migration writes its
     * whole state through {@code InstanceOperationGuard.stampMigrating}/{@code handoff},
     * which are set-based {@code updateAll} calls and fire NO write hooks. The
     * {@code ActivityLog.withAction} wrapper the drain row action used to carry recorded
     * literally nothing -- withAction only RENAMES hook-written rows, and there were
     * none. Traced 2026-08-07.
     */
    private static void recordMigration(int instanceId, String sourceName, String targetName) {
        ActivityLog.record(Models.get(InstanceModel.class), instanceId,
            ACTIVITY_MIGRATE_ACTION, sourceName + " -> " + targetName);
    }

    private static @NonNull List<Row> instancesOn(int serverId) {
        var query = Models.get(InstanceModel.class).find()
            .where(InstanceModel.DELETED_AT.isNull());
        if (serverId == ServerModel.localServerId()) {
            query = query.where(Criteria.or(
                InstanceModel.SERVER_ID.isNull(), InstanceModel.SERVER_ID.eq(serverId)));
        } else {
            query = query.where(InstanceModel.SERVER_ID.eq(serverId));
        }
        return query.all();
    }

    // -- interrupted-migration recovery -------------------------------------------

    /**
     * Boot recovery: settle every record a killed controller left mid-migration.
     * Rules, in order, all decided from daemon attribution ({@code claimOf}):
     * the record's host still holds the workload -> ROLL BACK (delete an OURS
     * destination copy, close the window, STOPPED); the record's host holds nothing
     * but the destination holds OURS -> COMPLETE the handoff (the copies were equal
     * by construction -- the source was stopped before export); neither holds it ->
     * ERROR, loudly. An unreachable daemon defers the settle to the next boot rather
     * than manufacturing a verdict. Never auto-started: recovery restores a single
     * truthful owner, an operator restores service.
     */
    public static void recoverInterrupted() {
        List<Row> stuck = Models.get(InstanceModel.class).find()
            .where(InstanceModel.DELETED_AT.isNull())
            .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_MIGRATING))
            .all();
        if (stuck.isEmpty()) {
            return;
        }
        InstanceMigrations migrations = new InstanceMigrations();
        for (Row row : stuck) {
            int id = row.get(InstanceModel.ID);
            Integer serverId = row.get(InstanceModel.SERVER_ID);
            try {
                // The same borrowed-lease discipline InstanceService.recoverInterrupted
                // documents: settle() takes the SOURCE host's fence, so an unguarded sweep
                // would seize (and keep) the lease of every host a stuck record sits on --
                // including hosts a rival controller is actively driving.
                Runnable settle = () -> {
                    if (!migrations.settle(id)) {
                        Blast.log("MIGRATE: could not settle interrupted migration of",
                            id, "- a daemon did not answer; retried at the next boot");
                    }
                };
                if (serverId == null) {
                    settle.run();
                } else {
                    BootSettle.underBorrowedHostLease(
                        migrations.instances.leases(), serverId, settle);
                }
            } catch (RuntimeException error) {
                Blast.log("MIGRATE: settling interrupted migration of", id,
                    "failed:", error.getMessage());
            }
        }
    }

    /**
     * Settle one mid-migration record (see {@link #recoverInterrupted} for the rules).
     *
     * @return true when the record was settled, false when a daemon did not answer
     */
    boolean settle(int instanceId) {
        Resolved resolved = this.instances.resolve(instanceId);
        Row row = resolved.row();
        Integer targetId = row.get(InstanceModel.MIGRATE_TARGET_ID);
        if (!InstanceModel.STATUS_MIGRATING.equals(row.get(InstanceModel.STATUS))
                || targetId == null) {
            return true;   // nothing mid-flight
        }
        if (!(resolved.runtime() instanceof WorkloadAttribution sourceAttribution)) {
            // Every transport requires attribution on both ends, so a runtime without
            // it cannot have been moved by this lane; nothing daemon-side to settle.
            return true;
        }
        String targetName = ServerModel.nameOf(targetId);
        // Same funnel as the submit lane: a thrown Violations reaches settle's callers
        // as the loud "settling failed" log line instead of a raw HostTrustException.
        InstanceRuntime targetRuntime = InstanceService.runtimeFor(resolved.handler(),
            targetName);
        WorkloadAttribution targetAttribution = targetRuntime instanceof WorkloadAttribution t
            ? t : null;

        WorkloadClaim sourceClaim;
        WorkloadClaim targetClaim;
        try {
            sourceClaim = sourceAttribution.claimOf(resolved.spec());
            targetClaim = targetAttribution != null
                ? targetAttribution.claimOf(resolved.spec()) : WorkloadClaim.ABSENT;
        } catch (IOException unreachable) {
            return false;   // refusing to answer is not evidence; defer
        }

        long sourceFence = this.instances.leases().requireFence(resolved.serverId());
        String handle = resolved.spec().handle();
        if (sourceClaim == WorkloadClaim.OURS) {
            // Roll back: the record's host is the data authority and still holds it.
            if (targetClaim == WorkloadClaim.OURS) {
                try {
                    removeMigrationCopy(targetRuntime, resolved);
                } catch (IOException undeletable) {
                    Blast.log("MIGRATE: rollback of", handle, "could not remove the"
                        + " destination copy on", targetName, ":", undeletable.getMessage());
                    return false;
                }
            }
            InstanceOperationGuard.clearMigration(this.instances.leases(), instanceId,
                resolved.serverId(), sourceFence, targetId,
                InstanceModel.STATUS_STOPPED, nameOf(row));
            Blast.log("MIGRATE: rolled back interrupted migration of", handle,
                "- source host keeps it");
            return true;
        }
        if (targetClaim == WorkloadClaim.OURS) {
            // Forward: the only copy lives on the destination; complete the handoff.
            long targetFence = this.instances.leases().requireFence(targetId);
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, instanceId);
            InstanceOperationGuard.handoff(this.instances.leases(), instanceId,
                resolved.serverId(), sourceFence, targetId, targetFence,
                InstanceModel.STATUS_STOPPED, nameOf(row));
            Blast.log("MIGRATE: completed interrupted migration of", handle,
                "onto", targetName);
            return true;
        }
        // Neither daemon holds an attributable copy: loud, never silent. The record stays
        // where it is, so the window's destination booking goes back like a rollback's.
        InstanceOperationGuard.clearMigration(this.instances.leases(), instanceId,
            resolved.serverId(), sourceFence, targetId,
            InstanceModel.STATUS_ERROR, nameOf(row));
        Blast.log("MIGRATE: interrupted migration of", handle, "found NO copy on either"
            + " host; the record is stamped error for the operator");
        return true;
    }

    /** The catch-path settle: never masks the original failure with its own. */
    private boolean settleQuietly(int instanceId) {
        try {
            return settle(instanceId);
        } catch (RuntimeException settleFailed) {
            Blast.log("MIGRATE: could not settle", instanceId, "after a failed step:",
                settleFailed.getMessage());
            return false;
        }
    }

    private void redeployBestEffort(int instanceId) {
        try {
            TenantWrites.inAuthorizedOperation(() -> this.instances.deploy(instanceId));
        } catch (RuntimeException redeployFailed) {
            Blast.log("MIGRATE: could not restart instance", instanceId,
                "after a rolled-back migration:", redeployFailed.getMessage());
        }
    }

    // -- the transport seam -------------------------------------------------------

    /**
     * One cold-transfer transport: how the payload leaves the source daemon and lands on
     * the destination. The orchestration around it (window, fences, ledger, settle) is
     * transport-agnostic by design -- see the class AIDEV-NOTEs.
     */
    private interface Transport {

        /** Stage the whole payload into {@code staging}; returns the total bytes staged. */
        long exportFromSource(@NonNull Path staging) throws IOException;

        /** Materialize the staged payload on the destination as a STOPPED workload. */
        void importOnTarget() throws IOException;

        /**
         * Remove what the source daemon holds BESIDE the container after a verified
         * import (the volume transport's named volumes); the native transport holds
         * nothing beside it.
         */
        void removeSourceRemnants() throws IOException;
    }

    /** Whether a runtime can carry a cold transfer at all (either transport shape). */
    private static boolean hasTransport(@NonNull InstanceRuntime runtime) {
        return runtime instanceof NativeSnapshotSupport
            || (runtime instanceof VolumeSnapshotSupport
                && runtime instanceof WorkloadAttribution);
    }

    /**
     * The transport this source/destination pair can carry, or null for the named
     * {@code migrate_unsupported} refusal. Native (whole-instance export/import, the
     * Incus tier) when BOTH ends speak it; else the volume transport (cold capture of
     * the declared logical volumes + recreate-and-restore, the Docker tier), which
     * additionally demands {@link WorkloadAttribution} on both ends -- the pre-flight
     * claim and the crash settle are what make the move safe, so a driver that cannot
     * answer the ownership question does not get to move data.
     */
    private @Nullable Transport transportFor(@NonNull Resolved resolved,
                                             @NonNull InstanceRuntime targetRuntime) {
        if (resolved.runtime() instanceof NativeSnapshotSupport sourceNative
                && targetRuntime instanceof NativeSnapshotSupport targetNative) {
            return new NativeTransport(resolved, sourceNative, targetNative);
        }
        if (resolved.runtime() instanceof VolumeSnapshotSupport sourceVolumes
                && resolved.runtime() instanceof WorkloadAttribution
                && targetRuntime instanceof VolumeSnapshotSupport targetVolumes
                && targetRuntime instanceof WorkloadAttribution) {
            return new VolumeTransport(resolved, sourceVolumes, targetVolumes, targetRuntime);
        }
        return null;
    }

    /** The daemon's own whole-instance export/import (snapshots ride along). */
    private static final class NativeTransport implements Transport {

        private final @NonNull Resolved resolved;
        private final @NonNull NativeSnapshotSupport source;
        private final @NonNull NativeSnapshotSupport target;
        private @Nullable Path export;

        NativeTransport(@NonNull Resolved resolved, @NonNull NativeSnapshotSupport source,
                        @NonNull NativeSnapshotSupport target) {
            this.resolved = resolved;
            this.source = source;
            this.target = target;
        }

        @Override
        public long exportFromSource(@NonNull Path staging) throws IOException {
            this.export = staging.resolve("instance.tar");
            return this.source.exportBackup(this.resolved.spec(), this.export,
                InstanceSnapshots.maxArchiveBytes(), true);
        }

        @Override
        public void importOnTarget() throws IOException {
            if (this.export == null) {
                throw new IOException("importOnTarget before exportFromSource");
            }
            this.target.importBackup(this.resolved.spec(), this.export);
        }

        @Override
        public void removeSourceRemnants() {
            // The whole instance IS the export; destroy already removed everything.
        }
    }

    /**
     * The volume transport: cold capture of the DECLARED logical volumes, recreate the
     * workload from its spec on the destination, restore the payloads into the freshly
     * minted volumes. What travels is exactly what a backup of this kind captures --
     * the declared volumes plus the record's own configuration (the deploy funnel
     * re-stages config files and re-attaches links on the destination).
     */
    private static final class VolumeTransport implements Transport {

        private final @NonNull Resolved resolved;
        private final @NonNull VolumeSnapshotSupport source;
        private final @NonNull VolumeSnapshotSupport target;
        private final @NonNull InstanceRuntime targetRuntime;
        private final @NonNull Map<String, String> volumes;
        private final @NonNull Map<String, Path> tars = new LinkedHashMap<>();

        VolumeTransport(@NonNull Resolved resolved, @NonNull VolumeSnapshotSupport source,
                        @NonNull VolumeSnapshotSupport target,
                        @NonNull InstanceRuntime targetRuntime) {
            this.resolved = resolved;
            this.source = source;
            this.target = target;
            this.targetRuntime = targetRuntime;
            this.volumes = InstanceSnapshots.logicalVolumes(resolved);
        }

        @Override
        public long exportFromSource(@NonNull Path staging) throws IOException {
            long total = 0;
            for (var captured : this.source.captureVolumes(this.resolved.spec(),
                    this.volumes, staging, InstanceSnapshots.maxArchiveBytes())) {
                this.tars.put(captured.name(), captured.file());
                total += captured.size();
            }
            return total;
        }

        @Override
        public void importOnTarget() throws IOException {
            // Leftover same-named volumes on the destination (an earlier attempt's
            // debris the container-claim pre-flight cannot see) would make the
            // extraction a MERGE over stale data -- the lie shaped like a success.
            // removeVolumesForRestore removes OURS, no-ops on absent and REFUSES a
            // foreign same-named volume, which is exactly the collision gate needed.
            this.target.removeVolumesForRestore(this.resolved.spec(), this.volumes,
                this.volumes.keySet());
            this.targetRuntime.create(this.resolved.spec());
            this.target.restoreVolumes(this.resolved.spec(), this.volumes, this.tars);
        }

        @Override
        public void removeSourceRemnants() throws IOException {
            this.source.removeVolumesForRestore(this.resolved.spec(), this.volumes,
                this.volumes.keySet());
        }
    }

    /**
     * Remove a same-record migration copy from a daemon: the container and, on a
     * volume-transport runtime, the named volumes a later import would otherwise
     * merge-restore over. The caller has already established the claim is OURS.
     * Package-visible for {@code InstanceService.destroy}'s abandon-ship cleanup.
     */
    static void removeMigrationCopy(@NonNull InstanceRuntime runtime,
                                    @NonNull Resolved resolved) throws IOException {
        runtime.destroy(resolved.spec().handle());
        if (runtime instanceof VolumeSnapshotSupport volumes) {
            Map<String, String> logical = InstanceSnapshots.logicalVolumes(resolved);
            volumes.removeVolumesForRestore(resolved.spec(), logical, logical.keySet());
        }
    }

    /** Attached device rows of one instance; unmovable this wave, refused by name. */
    private static long deviceCountOf(int instanceId) {
        return Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId))
            .count();
    }

    private static @NonNull String nameOf(@NonNull Row row) {
        return String.valueOf((Object) row.get(InstanceModel.NAME));
    }

    private static Path stagingRoot() {
        return Path.of(HohenheimSettings.VALUES.getValue(HohenheimSettings.Backup.STAGING_PATH));
    }

    private static Violations refusal(String key, Row row, Exception cause) {
        return Violations.ofForm(violationText(key)
            .withArg("name", nameOf(row))
            .withArg("reason", InstanceSnapshots.describe(cause)));
    }

    private static Microcopy violationText(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
