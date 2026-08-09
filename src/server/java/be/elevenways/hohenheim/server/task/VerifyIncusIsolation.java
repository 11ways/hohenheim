package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reconciler that closes the Incus kernel-isolation window: every running Incus
 * workload's live tap is re-checked against the daemon host's ACTUAL nftables, repaired
 * through the daemon's own re-apply lever, and STOPPED when the repair does not take.
 *
 * AIDEV-NOTE: why a SWEEP and not a check at start. The divergence is created INSIDE
 * incusd, after start, when it restarts QEMU for a guest reset a tenant can force at
 * will (upstream v7.3.0: a failed NIC detach skips the firewall teardown, leaving chains
 * that name the dead tap while the new tap matches nothing -- see IncusKernelIsolation).
 * Deploy-time verification cannot see a hole that does not exist yet, and there is no
 * event we can hang a re-check on that is not itself racing the daemon's own stop/start.
 * Nothing outside incusd can PREVENT the window opening; what a sweep does is make it
 * BOUNDED and self-closing -- there is no state in which a diverged workload stays
 * reachable indefinitely or invisibly, which is the difference between closing the hole
 * and merely checking more often. Upstream has no reconciler of its own (confirmed
 * against v7.3.0 and main, 2026-08-05), and no released version fixes the race, so this
 * is a permanent mechanism and not a version-gated bridge.
 *
 * AIDEV-NOTE: decided 2026-08-05 -- a workload whose isolation cannot be restored is
 * STOPPED, and that is deliberate even though stopping a tenant's VM is a real
 * availability decision. The repair runs FIRST, so a transient daemon race costs no
 * availability at all; only a workload the daemon REFUSES to re-isolate is stopped. What
 * is at stake in the other direction is not the workload's own data but every OTHER
 * tenant's: an unisolated VM reaches its peers, the host and the metadata range, and it
 * is the only strong boundary we have on shared iron. A stopped workload is a visible,
 * recoverable failure an operator can act on; an unisolated running one is an invisible,
 * unrecoverable one nobody learns about. A host whose kernel cannot be read at all is a
 * different case and is NOT stopped: it is reported every run as unverifiable, because
 * refusing to answer is not evidence of a leak.
 */
public class VerifyIncusIsolation extends ScheduledTask {

    public static final String STATIC_DESCRIPTION =
        "Verify Incus workload isolation in the daemon host's kernel";

    /** One host's outcome; every list names workloads, never a bare count. */
    public record HostOutcome(@NonNull String server, boolean verifiable,
                              @NonNull List<String> enforced, @NonNull List<String> repaired,
                              @NonNull List<String> stopped, @NonNull List<String> errors) {
    }

    @Override
    public @NonNull VerifyIncusIsolation newTask() {
        return new VerifyIncusIsolation();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // Every five minutes: the window this bounds is opened by a tenant-forcible guest
        // reset, so the cadence IS the exposure. Boot included -- a controller that was
        // down while a daemon restarted a VM must not inherit the hole silently.
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.bootAndCron("*/5 * * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        for (HostOutcome outcome : sweep()) {
            if (!outcome.verifiable()) {
                Blast.log("INCUS ISOLATION:", outcome.server(),
                    "cannot be kernel-verified (no nft lane to the daemon's host);"
                        + " its workloads' isolation is UNCONFIRMED");
                continue;
            }
            if (!outcome.repaired().isEmpty() || !outcome.stopped().isEmpty()
                    || !outcome.errors().isEmpty()) {
                Blast.log("INCUS ISOLATION:", outcome.server(), "- enforced",
                    outcome.enforced().size(), ", repaired", outcome.repaired(),
                    ", STOPPED", outcome.stopped(), ", errors", outcome.errors());
            }
        }
    }

    /**
     * Sweep every Incus host that carries a live workload; the executor only reports.
     *
     * AIDEV-NOTE: the status filter is {@link InstanceModel#LIVE_GUEST_STATUSES}, shared
     * with {@link VerifyWorkloadIsolation}, and it must stay shared. This sweep used to
     * filter {@code running} alone, so an Incus workload sitting in {@code starting} -- the
     * status deploy stamps for every template with a readiness line -- was on the bridge and
     * never kernel-verified. Two sweeps of the same design answering the same question
     * differently is how one of them ends up wrong without anyone noticing.
     */
    public static @NonNull List<HostOutcome> sweep() {
        Map<Integer, List<Row>> byServer = new LinkedHashMap<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .where(InstanceModel.STATUS.in(InstanceModel.LIVE_GUEST_STATUSES))
                .all()) {
            Object raw = instance.get(InstanceModel.SERVER_ID);
            if (raw instanceof Number serverId) {
                byServer.computeIfAbsent(serverId.intValue(), key -> new ArrayList<>())
                    .add(instance);
            }
        }

        List<HostOutcome> outcomes = new ArrayList<>();
        for (Map.Entry<Integer, List<Row>> entry : byServer.entrySet()) {
            Row server = Models.get(ServerModel.class).findById(entry.getKey());
            if (server == null || !ServerModel.isIncus(server)) {
                continue;
            }
            outcomes.add(sweepHost(server, entry.getValue()));
        }
        return List.copyOf(outcomes);
    }

    private static @NonNull HostOutcome sweepHost(@NonNull Row server,
                                                  @NonNull List<Row> instances) {
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        IncusKernelIsolation isolation;
        try {
            isolation = IncusKernelIsolation.forServer(server);
        } catch (RuntimeException unreachable) {
            return new HostOutcome(name, false, List.of(), List.of(), List.of(),
                List.of(String.valueOf(unreachable.getMessage())));
        }
        if (!isolation.available()) {
            return new HostOutcome(name, false, List.of(), List.of(), List.of(), List.of());
        }

        List<String> enforced = new ArrayList<>();
        List<String> repaired = new ArrayList<>();
        List<String> stopped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Row instance : instances) {
            int id = ((Number) instance.get(InstanceModel.ID)).intValue();
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            boolean diverged;
            try {
                diverged = !isolation.inspect(handle).enforced();
            } catch (IOException unreadable) {
                errors.add(handle + ": " + unreadable.getMessage());
                continue;
            }
            if (!diverged) {
                enforced.add(handle);
                continue;
            }
            try {
                isolation.enforce(handle);
                repaired.add(handle);
            } catch (IOException unrepairable) {
                // The declared refusal: unisolated and unrepairable means not reachable.
                errors.add(handle + ": " + unrepairable.getMessage());
                try {
                    new InstanceService().stop(id);
                    stopped.add(handle);
                } catch (RuntimeException stopFailed) {
                    errors.add(handle + ": could not stop an unisolated workload: "
                        + stopFailed.getMessage());
                }
            }
        }
        return new HostOutcome(name, true, List.copyOf(enforced), List.copyOf(repaired),
            List.copyOf(stopped), List.copyOf(errors));
    }
}
