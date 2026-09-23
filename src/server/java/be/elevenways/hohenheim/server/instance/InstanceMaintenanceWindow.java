package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.instance.InstanceService.Resolved;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;

/**
 * THE protected-status window a capture or restore runs its work inside: settle the
 * workload, stamp the protected status, run the work, and end the window whatever the
 * work threw.
 *
 * AIDEV-NOTE: this sequence used to be written out six times (snapshot capture and
 * restore on both lanes, backup capture on both lanes, restore-to-new), each with its own
 * exception net, and several nets caught only IOException. A named refusal thrown in the
 * middle (RestoreCapacity.require, backup_payload_mismatch, a fenced-out stamp of a helper)
 * therefore left the record {@code restoring} or {@code capturing} with the workload down
 * until the next boot settled it -- every power verb refuses a protected status, so the
 * operator could not even start it again. The window is ended here in ONE place and for
 * every throwable, and the caller still sees the work's own failure first.
 *
 * The migration window is NOT this shape and does not use it: its failure is settled from
 * daemon truth (InstanceMigrations.settle decides rollback or forward completion), never
 * by stamping a fixed status.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class InstanceMaintenanceWindow {

    private InstanceMaintenanceWindow() {
    }

    /** The work done while the protected status holds the record. */
    @FunctionalInterface
    interface Work {
        void run() throws IOException;
    }

    /** What the record becomes when the work fails. */
    enum Failure {

        /**
         * The work changed no workload data (a capture): the record settles exactly as on
         * success and a workload the window stopped is started again, best effort.
         */
        HAND_BACK,

        /**
         * Past the point of no return (a restore): the payload may be half-written, so the
         * record is stamped {@code error} and nothing is started -- an operator decides.
         */
        HOLD_ERROR
    }

    /**
     * One window's shape.
     *
     * @param protectedStatus the status that holds the record while the work runs
     * @param stopFirst       stop the workload through the ordinary stop funnel first
     * @param settledStatus   the status the record ends in when the work succeeded
     * @param redeploy        start the workload again after the window (a stopped one)
     * @param onFailure       what a failed work leaves behind
     */
    record Plan(@NonNull String protectedStatus, boolean stopFirst,
                @NonNull String settledStatus, boolean redeploy, @NonNull Failure onFailure) {
    }

    /**
     * Run {@code work} inside the window described by {@code plan}.
     *
     * A failure of the post-success redeploy propagates unchanged (the deploy stamped its own
     * outcome); a failure of the post-failure hand-back is attached to the work's failure as
     * suppressed, so the work's failure stays primary.
     *
     * @throws IOException whatever the work threw, after the window ended
     */
    static void run(@NonNull InstanceService instances, @NonNull Resolved resolved,
                    @NonNull Plan plan, @NonNull Work work) throws IOException {
        int instanceId = resolved.row().get(InstanceModel.ID);
        Object name = resolved.row().get(InstanceModel.NAME);
        if (plan.stopFirst()) {
            TenantWrites.inAuthorizedOperation(() -> instances.stop(instanceId));
        }
        long fence = instances.leases().requireFence(resolved.serverId());
        InstanceOperationGuard.stamp(instances.leases(), instanceId, resolved.serverId(), fence,
            plan.protectedStatus(), name);
        try {
            work.run();
        } catch (IOException | RuntimeException | Error failure) {
            endAfterFailure(instances, resolved, plan, fence, failure);
            throw failure;
        }
        InstanceOperationGuard.stamp(instances.leases(), instanceId, resolved.serverId(), fence,
            plan.settledStatus(), name);
        if (plan.redeploy()) {
            TenantWrites.inAuthorizedOperation(() -> instances.deploy(instanceId));
        }
    }

    /** Replace the protected status after a failed work; never masks the work's failure. */
    private static void endAfterFailure(@NonNull InstanceService instances,
                                        @NonNull Resolved resolved, @NonNull Plan plan,
                                        long fence, @NonNull Throwable failure) {
        int instanceId = resolved.row().get(InstanceModel.ID);
        String ended = switch (plan.onFailure()) {
            case HAND_BACK -> plan.settledStatus();
            case HOLD_ERROR -> InstanceModel.STATUS_ERROR;
        };
        try {
            InstanceOperationGuard.stamp(instances.leases(), instanceId, resolved.serverId(),
                fence, ended, resolved.row().get(InstanceModel.NAME));
        } catch (RuntimeException stampFailed) {
            // Fenced out: the record is a rival controller's now and so is its settle.
            failure.addSuppressed(stampFailed);
            Blast.log("INSTANCE: could not end the", plan.protectedStatus(), "window of",
                instanceId, "-", stampFailed.getMessage());
            return;
        }
        boolean handBack = switch (plan.onFailure()) {
            case HAND_BACK -> plan.redeploy();
            case HOLD_ERROR -> false;
        };
        if (!handBack) {
            return;
        }
        try {
            TenantWrites.inAuthorizedOperation(() -> instances.deploy(instanceId));
        } catch (RuntimeException restartFailed) {
            failure.addSuppressed(restartFailed);
            Blast.log("INSTANCE: could not restart instance", instanceId, "after a failed",
                plan.protectedStatus(), "window -", restartFailed.getMessage());
        }
    }
}
