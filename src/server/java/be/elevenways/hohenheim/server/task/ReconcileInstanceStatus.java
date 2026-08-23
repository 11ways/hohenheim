package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Corrects every instance record whose stored status claims a live workload the runtime
 * does not have -- the sweeper half of {@link InstanceStatusReconciler}.
 */
public class ReconcileInstanceStatus extends ScheduledTask {

    public static final String STATIC_DESCRIPTION =
        "Reconcile instance status against what the runtimes actually run";

    @Override
    public @NonNull ReconcileInstanceStatus newTask() {
        return new ReconcileInstanceStatus();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // EVERY TWO MINUTES, and the cadence is the deliberate half of this task.
        //
        // What it costs: one state read per record that CLAIMS a live workload -- the
        // same single call the Overview already makes when an operator opens the page,
        // and nothing at all for a record that is created, stopped or errored. It is the
        // cheapest question the driver seam answers.
        //
        // What it buys: the window during which the product lies about whether someone's
        // box is up. A crashed workspace used to show green forever; at ten minutes
        // (ObserveInstanceDisk's cadence, for a signal that moves slowly by nature) a
        // developer watching their own deploy would still be reading a stale pill long
        // after they gave up. Two minutes is short enough that the badge is believable
        // and long enough that a busy estate is not dialling its daemons constantly.
        //
        // AIDEV-NOTE: the cost that would push this out is a large estate of workloads on
        // REMOTE hosts, where each read is an ssh round trip rather than a local socket
        // call. Batching per host (one daemon list instead of N state reads, the shape
        // DockerReconciler.sweepAll already uses) is the answer there, NOT a rarer sweep.
        //
        // Gated on INSTANCES: the role that owns the workloads whose status this is.
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("*/2 * * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        List<InstanceStatusReconciler.Outcome> outcomes = new InstanceStatusReconciler().sweep();
        long corrected = outcomes.stream()
            .filter(o -> o.verdict() == InstanceStatusReconciler.Verdict.CORRECTED).count();
        long unasked = outcomes.stream()
            .filter(o -> o.verdict() == InstanceStatusReconciler.Verdict.COULD_NOT_ASK).count();
        if (corrected > 0 || unasked > 0) {
            Blast.log("INSTANCE RECONCILE:", outcomes.size(), "checked,", corrected,
                "corrected,", unasked, "could not be asked");
        }
    }
}
