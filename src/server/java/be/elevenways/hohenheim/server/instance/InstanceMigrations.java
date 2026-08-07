package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport;
import be.elevenways.hohenheim.server.runtime.NativeSnapshotSupport.WorkloadClaim;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * COLD migration of one instance between hosts, and the host DRAIN built on it.
 *
 * AIDEV-NOTE: THE migration-policy decision (2026-08-06, Phase 8 gate). Cold --
 * stop, whole-instance export, import on the destination, start -- is the chosen
 * policy; live migration is REJECTED for now (recorded in the plan's Proxmox-use
 * inventory): incus stateful transfer needs migration.stateful set before start,
 * CRIU for containers and matched CPU flags for VMs, plus a daemon-to-daemon trust
 * relationship this product deliberately does not hold. The TRANSPORT is the
 * existing NativeSnapshotSupport export/import pair, controller-mediated (daemon A
 * -> controller staging -> daemon B): it already carries re-attribution, the MAC
 * strip and the isolation rejoin, and the controller already holds pinned trust
 * with each daemon separately -- incus's own cross-host copy would be a second
 * transfer path riding a trust relationship that exists nowhere else in the product.
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
        if (target == null || !ServerModel.runtimeOf(target)
                .equals(resolved.handler().requiredRuntime())) {
            throw Violations.ofForm(violationText("host_runtime_mismatch")
                .withArg("name", ServerModel.nameOf(targetServerId))
                .withArg("runtime", target != null ? ServerModel.runtimeOf(target) : "absent")
                .withArg("required", resolved.handler().requiredRuntime()));
        }
        HostAdmission.requireInstancePlacement(targetServerId);

        if (!(resolved.runtime() instanceof NativeSnapshotSupport sourceSupport)) {
            throw Violations.ofForm(violationText("migrate_unsupported")
                .withArg("name", nameOf(resolved.row())));
        }
        String targetName = ServerModel.nameOf(targetServerId);
        InstanceRuntime targetRuntime = resolved.handler().runtimeFor(targetName);
        if (!(targetRuntime instanceof NativeSnapshotSupport targetSupport)) {
            throw Violations.ofForm(violationText("migrate_unsupported")
                .withArg("name", nameOf(resolved.row())));
        }
        // Device rows are UNMOVABLE this wave, refused by name: the whole-instance
        // export does not carry custom volumes, and the destination deploy's device
        // reconcile would attach FRESH EMPTY volumes -- a migration that "succeeds"
        // while silently emptying a tenant's disk is exactly the silent-success shape
        // this refusal exists to kill. Extra NICs are refused with them: their bridge
        // is host-local and an import naming it can fail or dangle.
        long devices = Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId))
            .count();
        if (devices > 0) {
            throw Violations.ofForm(violationText("migrate_devices_present")
                .withArg("name", nameOf(resolved.row()))
                .withArg("count", devices));
        }
        // A port publication is a host-scoped reservation (DNS may point at it);
        // no migratable kind carries one today, and moving one silently would strand
        // the claim -- refused by name until a consumer needs it.
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
        try {
            WorkloadClaim claim = targetSupport.claimOf(resolved.spec());
            if (claim == WorkloadClaim.FOREIGN) {
                throw Violations.ofForm(violationText("migrate_destination_occupied")
                    .withArg("name", nameOf(resolved.row()))
                    .withArg("server", targetName));
            }
            if (claim == WorkloadClaim.OURS) {
                targetRuntime.destroy(handle);
            }
        } catch (IOException unreachable) {
            throw refusal("instance_migrate_failed", resolved.row(), unreachable);
        }

        InstanceOperationGuard.stampMigrating(this.instances.leases(), instanceId,
            resolved.serverId(), sourceFence, targetServerId, nameOf(resolved.row()));
        Path staging = stagingRoot().resolve("migrate-" + instanceId + "-"
            + STAMP.format(Instant.now()));
        try {
            InstanceConsoles.markStopExpected(instanceId);
            InstanceConsoles.closeSession(instanceId);
            resolved.runtime().stop(handle, 10);
            PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, instanceId);
            this.checkpoint.accept("stopped");

            Files.createDirectories(staging);
            Path export = staging.resolve("instance.tar");
            long size = sourceSupport.exportBackup(resolved.spec(), export,
                InstanceSnapshots.maxArchiveBytes(), true);
            this.checkpoint.accept("exported");

            this.capacity.require(targetServerId, size);
            targetSupport.importBackup(resolved.spec(), export);
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
        return new DrainReport(moved, refused, complete);
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
            try {
                if (!migrations.settle(id)) {
                    Blast.log("MIGRATE: could not settle interrupted migration of",
                        id, "- a daemon did not answer; retried at the next boot");
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
        if (!(resolved.runtime() instanceof NativeSnapshotSupport sourceSupport)) {
            return true;   // cannot have been exported by this lane; nothing daemon-side
        }
        String targetName = ServerModel.nameOf(targetId);
        InstanceRuntime targetRuntime = resolved.handler().runtimeFor(targetName);
        NativeSnapshotSupport targetSupport = targetRuntime instanceof NativeSnapshotSupport t
            ? t : null;

        WorkloadClaim sourceClaim;
        WorkloadClaim targetClaim;
        try {
            sourceClaim = sourceSupport.claimOf(resolved.spec());
            targetClaim = targetSupport != null
                ? targetSupport.claimOf(resolved.spec()) : WorkloadClaim.ABSENT;
        } catch (IOException unreachable) {
            return false;   // refusing to answer is not evidence; defer
        }

        long sourceFence = this.instances.leases().requireFence(resolved.serverId());
        String handle = resolved.spec().handle();
        if (sourceClaim == WorkloadClaim.OURS) {
            // Roll back: the record's host is the data authority and still holds it.
            if (targetClaim == WorkloadClaim.OURS) {
                try {
                    targetRuntime.destroy(handle);
                } catch (IOException undeletable) {
                    Blast.log("MIGRATE: rollback of", handle, "could not remove the"
                        + " destination copy on", targetName, ":", undeletable.getMessage());
                    return false;
                }
            }
            InstanceOperationGuard.clearMigration(this.instances.leases(), instanceId,
                resolved.serverId(), sourceFence, InstanceModel.STATUS_STOPPED, nameOf(row));
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
        // Neither daemon holds an attributable copy: loud, never silent.
        InstanceOperationGuard.clearMigration(this.instances.leases(), instanceId,
            resolved.serverId(), sourceFence, InstanceModel.STATUS_ERROR, nameOf(row));
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
