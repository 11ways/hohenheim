package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.application.ApplicationUpstreams;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the RUNTIME what is actually running and corrects the stored status column, so a
 * workload that died without anyone watching stops reporting itself alive.
 *
 * AIDEV-NOTE: the defect this exists for (workspace-journey audit, defect 2). Nothing
 * else reconciles instance status: {@code InstanceConsoles.prepare} opens a supervising
 * watch ONLY for a template that declares a readiness line or a stop command, or for
 * {@code crash_policy == restart} -- and the DEFAULT workspace has no template and a
 * crash policy of {@code none}. Its status column is therefore whatever the last
 * operation stamped, forever, and the list pill and the Overview badge both render it.
 * A workspace whose start command died a minute after a successful build showed green
 * until someone pressed Deploy again.
 *
 * It asks the DRIVER SEAM ({@code InstanceRuntime.status}), never a kind-specific probe,
 * so every kind on both runtimes is covered by construction and a new kind inherits it.
 *
 * THE ONE RULE THIS MUST NEVER BREAK: {@link ContainerState#UNREACHABLE} is not evidence.
 * "The daemon says it is gone" ({@code ABSENT}/{@code STOPPED}) and "I could not ask"
 * ({@code UNREACHABLE}, an unresolvable record, a host that will not build a client) are
 * distinct answers here exactly as they are on the enum, and only the first ever writes.
 * A network blip that downgraded every workload on a host would be a worse lie than the
 * one this class fixes.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class InstanceStatusReconciler {

    /** The activity action a correction is recorded under. */
    public static final String ACTIVITY_RECONCILE_ACTION = "reconciled";

    /** What one record's reconciliation decided. */
    public enum Verdict {

        /** The daemon answered and agreed with the record; only the timestamp moved. */
        CONFIRMED,

        /** The daemon answered, disagreed, and the record was corrected to its answer. */
        CORRECTED,

        /** The daemon could not be asked: NOTHING was written, and nothing is claimed. */
        COULD_NOT_ASK,

        /** A transitional record (in flight here, held by a rival, or mid-install). */
        SKIPPED
    }

    /**
     * @param stored   the status the record carried when the sweep read it
     * @param observed the state the daemon answered with, or null when it never answered
     */
    public record Outcome(int instanceId, @NonNull Verdict verdict, @NonNull String stored,
                          ContainerState observed) {
    }

    private final @NonNull InstanceService instances;

    public InstanceStatusReconciler() {
        this(new InstanceService());
    }

    public InstanceStatusReconciler(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * The statuses that CLAIM a live workload and are therefore worth checking.
     *
     * AIDEV-NOTE: deliberately not {@code InstanceModel.LIVE_GUEST_STATUSES}, which is the
     * isolation sweeps' list and includes {@code error} on purpose ("the daemon may
     * disagree" -- an error record may well name a live guest a firewall must still
     * verify). Correcting an {@code error} record to {@code stopped} would OVERWRITE the
     * one status that tells an operator their last operation failed, replacing a reason
     * with a shrug. {@code created} and {@code stopped} claim nothing live, so a sweep of
     * them could only ever confirm what the record already says.
     */
    private static @NonNull Criteria claimsALiveWorkload() {
        return Criteria.or(
            InstanceModel.STATUS.eq(InstanceModel.STATUS_RUNNING),
            InstanceModel.STATUS.eq(InstanceModel.STATUS_STARTING));
    }

    /** Reconcile every record whose status claims a live workload. */
    public @NonNull List<Outcome> sweep() {
        List<Outcome> outcomes = new ArrayList<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .where(claimsALiveWorkload())
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null) {
                continue;
            }
            try {
                outcomes.add(reconcile(id));
            } catch (RuntimeException failed) {
                // One bad record must never end the sweep for the rest of the estate.
                Blast.log("INSTANCE RECONCILE: could not reconcile instance", id, "-",
                    failed.getMessage());
                outcomes.add(new Outcome(id, Verdict.COULD_NOT_ASK,
                    String.valueOf((Object) instance.get(InstanceModel.STATUS)), null));
            }
        }
        return List.copyOf(outcomes);
    }

    /**
     * Reconcile ONE record.
     *
     * <p>Transitional records are skipped by three independent guards, each answering a
     * different question: this controller's own in-flight mark
     * ({@code InstanceService.hasOperationInFlight}) for a deploy/stop/destroy we are
     * driving right now, the install lifecycle for a template whose install step has not
     * finished, and the borrowed HOST LEASE for a rival controller's work.</p>
     */
    public @NonNull Outcome reconcile(int instanceId) {
        Row instance = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
        if (instance == null) {
            return new Outcome(instanceId, Verdict.SKIPPED, "", null);
        }
        String stored = String.valueOf((Object) instance.get(InstanceModel.STATUS));

        // A protected status (capturing/restoring/migrating) is an operation in flight
        // with its OWN settle; the query does not select them, and this states why.
        if (!InstanceModel.isOperable(instance)) {
            return new Outcome(instanceId, Verdict.SKIPPED, stored, null);
        }
        // Mid-install: the deploy lane itself refuses here (requireInstalled), so the
        // record is between steps of an operation that will stamp its own outcome.
        if (!installSettled(instance)) {
            return new Outcome(instanceId, Verdict.SKIPPED, stored, null);
        }
        // Mid-deploy in THIS process: the record still carries its previous status while
        // the container is being replaced, and the daemon honestly disagrees.
        if (InstanceService.hasOperationInFlight(instanceId)) {
            return new Outcome(instanceId, Verdict.SKIPPED, stored, null);
        }

        int serverId = ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID));
        Outcome[] outcome = {new Outcome(instanceId, Verdict.SKIPPED, stored, null)};
        // The lease is BORROWED, never seized (BootSettle's contract): a host a rival
        // controller holds is that controller's to reconcile, and one taken purely to
        // look is handed straight back.
        boolean ran = BootSettle.underBorrowedHostLease(this.instances.leases(), serverId,
            () -> outcome[0] = observeAndCorrect(instanceId, serverId, stored));
        if (!ran) {
            return new Outcome(instanceId, Verdict.SKIPPED, stored, null);
        }
        return outcome[0];
    }

    /** Ask the daemon under the held lease, then write only what it actually answered. */
    private @NonNull Outcome observeAndCorrect(int instanceId, int serverId,
                                               @NonNull String stored) {
        InstanceStatus live;
        try {
            live = this.instances.liveStatus(instanceId);
        } catch (RuntimeException couldNotAsk) {
            // An unresolvable record or an unaddressable host: a NAMED refusal, and it
            // says nothing whatsoever about whether the workload is running.
            return new Outcome(instanceId, Verdict.COULD_NOT_ASK, stored, null);
        }
        ContainerState state = live.state();
        if (state == ContainerState.UNREACHABLE) {
            return new Outcome(instanceId, Verdict.COULD_NOT_ASK, stored, state);
        }

        String settled = settledStatusFor(stored, state);
        if (settled == null) {
            // A `starting` record whose container IS up: the container being up is not the
            // readiness the console matcher is still waiting for, so there is nothing here
            // this class is entitled to confirm.
            return new Outcome(instanceId, Verdict.SKIPPED, stored, state);
        }

        // Re-read: the daemon call is the widest window in this method, and a deploy that
        // landed inside it has already stamped the truth. Correcting over it would replace
        // a fresh outcome with a stale observation.
        Row fresh = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .where(InstanceModel.DELETED_AT.isNull())
            .first();
        if (fresh == null || !stored.equals(fresh.get(InstanceModel.STATUS))
                || InstanceService.hasOperationInFlight(instanceId)) {
            return new Outcome(instanceId, Verdict.SKIPPED, stored, state);
        }

        boolean changed = !settled.equals(stored);
        // A correction here is CRASH-SHAPED by construction: only live-claiming records
        // are swept, and every operator-driven stop stamps `stopped` synchronously, so a
        // record still claiming a live workload the daemon says is gone died with nobody
        // watching. The crash policy decides what that becomes -- the SAME vocabulary the
        // console watch enforces for supervised workloads, one lane later.
        boolean restart = changed && InstanceModel.CRASH_RESTART
            .equals(fresh.get(InstanceModel.CRASH_POLICY));
        boolean flapping = restart && InstanceConsoles.flapExceeded(instanceId);
        if (changed && (!restart || flapping)) {
            // AIDEV-NOTE: `error`, not `stopped` -- an operator stop and an unobserved
            // death must never render as the same pill, and the crashedInstances
            // attention item reads exactly this status. The console lane can additionally
            // see the exit CODE and lets a clean exit settle to `stopped`; the driver
            // seam exposes no exit code, so this lane names what it actually knows.
            settled = InstanceModel.STATUS_ERROR;
        }
        long fence = this.instances.leases().requireFence(serverId);
        InstanceOperationGuard.stampObserved(this.instances.leases(), instanceId, serverId,
            fence, settled, changed, String.valueOf((Object) fresh.get(InstanceModel.NAME)));
        if (!changed) {
            return new Outcome(instanceId, Verdict.CONFIRMED, stored, state);
        }
        // A CORRECTION is also a routing fact, and only a correction: this record just
        // stopped claiming a live workload, so every handler holding its address must ask
        // again ({@code InstanceModel.SERVABLE_STATUSES} is what the resolution reads). The
        // stamp above is a hook-free updateAll, so nothing else would ever notice -- and the
        // dead workload's OBSERVED port claim survives in the ledger, because only a settled
        // stop releases one. A mere confirmation moves no address and must bump nothing.
        ApplicationUpstreams.invalidateForInstance(instanceId);
        // A correction is accountability, not decoration: the record just contradicted
        // itself and the operator must be able to see when, and on whose evidence.
        ActivityLog.record(Models.get(InstanceModel.class), instanceId,
            ACTIVITY_RECONCILE_ACTION,
            stored + " -> " + settled + " (died without an observed stop; the daemon"
                + " reports the workload "
                + (state == ContainerState.ABSENT ? "absent" : "stopped")
                + (flapping ? "; crash loop, automatic restarts suspended" : "") + ")");
        Blast.log("INSTANCE RECONCILE:", fresh.get(InstanceModel.NAME), stored, "->",
            settled, "- daemon says", state);
        if (flapping) {
            InstanceConsoles.alertCrashLoop(instanceId, fresh.get(InstanceModel.NAME));
        } else if (restart) {
            redeployCrashed(instanceId, fresh.get(InstanceModel.NAME));
        }
        return new Outcome(instanceId, Verdict.CORRECTED, stored, state);
    }

    /**
     * The restart half of {@code crash_policy = restart} for UNWATCHED workloads: the
     * console watch restarts a supervised crash the moment it exits, and this is the same
     * decision taken at sweep cadence for a workload nothing was watching -- which is
     * also what brings restart-policy workloads back after a host reboot (their records
     * still claim {@code running}, the daemon says gone, the correction lands here).
     */
    private void redeployCrashed(int instanceId, Object name) {
        Blast.log("INSTANCE RECONCILE: instance", instanceId,
            "died without an observed stop; crash policy restart -> redeploying");
        // The datasource context does not cross threads on its own (the console watch
        // captures it the same way).
        var datasource = Db.currentOrDefault();
        Thread.startVirtualThread(() -> Db.run(datasource, () -> {
            try {
                this.instances.deploy(instanceId);
            } catch (RuntimeException refused) {
                Blast.log("INSTANCE RECONCILE: crash restart of instance", name,
                    "refused:", refused.getMessage());
            }
        }));
    }

    /**
     * The status the daemon's answer settles this record to, or null when the daemon's
     * answer does not settle it at all.
     *
     * AIDEV-NOTE: {@code ABSENT} and {@code STOPPED} both settle to {@code stopped}, the
     * same fold {@code InstanceService.settleInterrupted} makes -- the status vocabulary
     * has no "the container does not exist" member, and both answers mean the same thing
     * to every reader: nothing is serving and a deploy will bring it back. This is the
     * BASE fold; the caller overlays the crash policy on it (a correction is an
     * unobserved death, so policy none becomes {@code error} and policy restart
     * redeploys).
     */
    private static String settledStatusFor(@NonNull String stored,
                                           @NonNull ContainerState state) {
        if (state == ContainerState.RUNNING) {
            // Confirming `running` is exactly what this sweep is for; confirming
            // `starting` is not ours to do (readiness is the console matcher's).
            return InstanceModel.STATUS_RUNNING.equals(stored)
                ? InstanceModel.STATUS_RUNNING : null;
        }
        return InstanceModel.STATUS_STOPPED;
    }

    /** Whether the template install lifecycle is between steps right now. */
    private static boolean installSettled(@NonNull Row instance) {
        String state = instance.get(InstanceModel.INSTALL_STATE);
        return state == null || InstanceModel.INSTALL_NONE.equals(state)
            || InstanceModel.INSTALL_INSTALLED.equals(state);
    }
}
