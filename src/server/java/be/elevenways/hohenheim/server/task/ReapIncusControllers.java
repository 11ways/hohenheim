package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.incus.ControllerPresence;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusClients;
import be.elevenways.hohenheim.server.incus.IncusReaper;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The sweep that keeps a shared Incus daemon from accumulating one isolation ACL and one
 * bridge per controller that ever touched it: it REFRESHES this controller's presence
 * stamp, classifies every per-controller shared object on the host, REPORTS the whole
 * classification, and removes only the departed ones.
 *
 * AIDEV-NOTE: the stamp is written FIRST, before anything is judged, and that order is
 * the point. This sweep is the only thing that keeps a quiet controller's own objects
 * alive in every peer's eyes, so a run that judged before it stamped would be a run that
 * could let our own liveness lapse while we reasoned about someone else's.
 *
 * AIDEV-NOTE: this sweeps EVERY inventoried Incus host, not only hosts carrying running
 * instances (which is what {@link VerifyIncusIsolation} does, correctly, for its own
 * question). A controller with no workloads at all is exactly the controller whose stamp
 * must not lapse -- scoping this sweep to hosts with running instances would make an idle
 * controller stop stamping precisely while it looks most departed.
 *
 * The operator's preview: every candidate is logged with its verdict and the reason, on
 * every run, and the default grace is a week -- so an object is named in ~168 reports
 * before it is ever eligible. Removals are recorded on the HOST record's activity log.
 */
public class ReapIncusControllers extends ScheduledTask {

    public static final String STATIC_DESCRIPTION =
        "Refresh this controller's Incus presence and reap departed controllers' shared objects";

    /** The activity action one removal is recorded under, on the host record. */
    public static final String ACTIVITY_ACTION = "reaped_controller_objects";

    /** One host's outcome; every list names objects, never a bare count. */
    public record HostOutcome(@NonNull String server, boolean reachable,
                              @NonNull List<IncusReaper.Candidate> plan,
                              @NonNull List<String> removed, @NonNull List<String> refused,
                              @NonNull List<String> errors) {
    }

    @Override
    public @NonNull ReapIncusControllers newTask() {
        return new ReapIncusControllers();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // Every 15 minutes: the cadence is what keeps our own stamp inside
        // ControllerPresence.REFRESH_AFTER (30 minutes) with a missed run to spare. Boot
        // included -- a controller that was down must re-announce itself before it is
        // judged, not on the hour.
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.bootAndCron("*/15 * * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        for (HostOutcome outcome : sweep()) {
            if (!outcome.reachable()) {
                Blast.log("INCUS REAP:", outcome.server(), "unreachable -", outcome.errors());
                continue;
            }
            Blast.log("INCUS REAP:", outcome.server(), "-", IncusReaper.tally(outcome.plan()));
            for (IncusReaper.Candidate candidate : outcome.plan()) {
                if (candidate.verdict() != IncusReaper.Verdict.OURS) {
                    Blast.log("INCUS REAP:", outcome.server(), candidate.verdict(),
                        candidate.kind(), candidate.name(), "-", candidate.reason());
                }
            }
            if (!outcome.removed().isEmpty() || !outcome.refused().isEmpty()) {
                Blast.log("INCUS REAP:", outcome.server(), "removed", outcome.removed(),
                    ", refused", outcome.refused());
            }
        }
    }

    /** Sweep every inventoried Incus host; the executor only reports. */
    public static @NonNull List<HostOutcome> sweep() {
        List<HostOutcome> outcomes = new ArrayList<>();
        for (Row server : Models.get(ServerModel.class).find().all()) {
            if (ServerModel.isIncus(server)) {
                outcomes.add(sweepHost(server));
            }
        }
        return List.copyOf(outcomes);
    }

    /** Stamp, classify and (when allowed) reap one host. */
    public static @NonNull HostOutcome sweepHost(@NonNull Row server) {
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        IncusClient incus;
        try {
            incus = IncusClients.forServer(server);
        } catch (RuntimeException unreachable) {
            return unreachable(name, "could not address the daemon", unreachable);
        }

        try {
            ControllerPresence.stamp(incus);
        } catch (Exception unstamped) {
            // A host we cannot stamp is a host we may not judge: our own liveness there is
            // now as unprovable as anyone else's, and reaping on that footing would be
            // reaping on evidence we just failed to produce ourselves.
            return unreachable(name, "presence stamp failed", unstamped);
        }

        List<IncusReaper.Candidate> plan;
        try {
            plan = IncusReaper.plan(incus.networkAcls(), incus.networks(),
                ControllerIdentity.token(), Instant.now(), graceDuration());
        } catch (Exception unlistable) {
            return unreachable(name, "could not list shared objects", unlistable);
        }
        // The stamp round trip IS a successful daemon contact, so this sweep doubles as
        // the Incus host heartbeat -- the Docker tier's reconcile has done exactly this
        // for Docker hosts all along, and an Incus host had no positive signal at all.
        // Recorded only after the daemon actually ANSWERED (stamp written, objects
        // listed), never off a client object that merely constructed.
        HostProbe.recordSuccess(name);

        if (!reapingEnabled()) {
            return new HostOutcome(name, true, plan, List.of(), List.of(), List.of());
        }
        IncusReaper.Reaped reaped = IncusReaper.reap(incus, plan, false);
        for (String removed : reaped.removed()) {
            ActivityLog.record(Models.get(ServerModel.class), server.get(ServerModel.ID),
                ACTIVITY_ACTION, removed);
        }
        return new HostOutcome(name, true, plan, reaped.removed(), reaped.refused(), List.of());
    }

    /**
     * The OPERATOR's reap of one named host: the same classification, but unstamped
     * objects go too.
     *
     * AIDEV-NOTE: this is the only caller that passes {@code unstamped = true}, and it is
     * deliberately not reachable from any schedule. An unstamped object cannot be PROVEN
     * abandoned -- its controller may simply be running a build older than the presence
     * mechanism -- so removing it is a human judgement about one host a human named, never
     * a timer's conclusion. The removal is still re-verified at the daemon per object.
     *
     * @throws IllegalStateException naming the host when it cannot be listed, so the
     *                               operator never reads "nothing to reap" off a failure
     */
    public static IncusReaper.@NonNull Reaped reapIncludingUnstamped(@NonNull Row server) {
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        try {
            IncusClient incus = IncusClients.forServer(server);
            ControllerPresence.stamp(incus);
            List<IncusReaper.Candidate> plan = IncusReaper.plan(incus.networkAcls(),
                incus.networks(), ControllerIdentity.token(), Instant.now(), operatorGrace());
            IncusReaper.Reaped reaped = IncusReaper.reap(incus, plan, true);
            for (String removed : reaped.removed()) {
                ActivityLog.record(Models.get(ServerModel.class), server.get(ServerModel.ID),
                    ACTIVITY_ACTION, removed);
            }
            return reaped;
        } catch (Exception failed) {
            throw new IllegalStateException("Could not reap controller objects on '" + name
                + "': " + failed.getMessage(), failed);
        }
    }

    /**
     * The grace an OPERATOR-driven reap judges by.
     *
     * AIDEV-NOTE: it falls back to the DEFAULT grace rather than to zero when the setting
     * disables automatic reaping. Zero would make every peer's stamp read as expired and
     * turn "also take the unstamped ones" into "take everyone's", which is not what the
     * operator asked for -- switching the sweep off is a statement about the timer, not a
     * licence to widen what a click means.
     */
    private static @NonNull Duration operatorGrace() {
        Duration configured = graceDuration();
        return configured.isZero()
            ? Duration.ofHours(HohenheimSettings.Incus.CONTROLLER_PRESENCE_GRACE_HOURS
                .getDefaultValue())
            : configured;
    }

    /**
     * The configured grace, as a duration.
     *
     * @return {@link Duration#ZERO} when the setting disables expiry, which
     *         {@link #reapingEnabled()} already refuses on -- expiry that cannot be proven
     *         is never assumed
     */
    public static @NonNull Duration graceDuration() {
        Integer hours = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Incus.CONTROLLER_PRESENCE_GRACE_HOURS);
        return hours == null || hours <= 0 ? Duration.ZERO : Duration.ofHours(hours);
    }

    /** Whether an automatic sweep may remove anything at all. */
    public static boolean reapingEnabled() {
        return Boolean.TRUE.equals(HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Incus.REAP_DEPARTED_CONTROLLERS))
            && !graceDuration().isZero();
    }

    /**
     * A host this sweep could not reach: the typed verdict lands on the host record, so
     * an Incus host that stops answering is visible in the admin surface rather than only
     * in a log line.
     *
     * AIDEV-NOTE: scoped to Incus hosts by the caller ({@code ServerModel.isIncus}), and
     * it must stay that way. The hourly Docker reconcile once iterated EVERY host row and
     * stamped every Incus host UNREACHABLE because its client factory refuses an Incus
     * host by construction -- a structural non-answer recorded as a probe verdict. Never
     * widen either sweep to hosts of the other runtime.
     */
    private static HostOutcome unreachable(String name, String context, Exception error) {
        HostProbe.recordFailure(name, HostProbe.classify(error));
        return new HostOutcome(name, false, List.of(), List.of(), List.of(),
            List.of(context + ": " + error.getMessage()));
    }
}
