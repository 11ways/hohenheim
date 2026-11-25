package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.host.HostMemory;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.MemoryHealth;
import be.elevenways.hohenheim.server.instance.MemoryHistory;
import be.elevenways.hohenheim.server.runtime.StatsFeed;
import be.elevenways.hohenheim.server.runtime.StatsReading;
import be.elevenways.hohenheim.server.runtime.StatsStreamSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The memory sampler: one reading of every running app's memory use into {@link MemoryHistory}, and each measurable
 * host's available memory and swap onto its record ({@link HostMemory}), so the dashboard's memory checks judge
 * stored and remembered facts instead of dialling a daemon per render.
 *
 * AIDEV-NOTE: an app is read through the driver's own stats lane ({@link StatsStreamSupport}), one reading and closed,
 * so a third driver is sampled by its feed alone. A read that fails keeps the app's history (one blip is no reason to
 * start its windows again); only an app that stopped running is forgotten. The host stamp is a heartbeat's kind of
 * write: HostModel captures none of the facts, so it is bookkeeping, never activity.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class ObserveInstanceMemory extends ScheduledTask {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("observe_instance_memory");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull ObserveInstanceMemory newTask() {
        return new ObserveInstanceMemory();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // The sample interval is the history's own, so the freshness its checks demand follows any change here.
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("*/" + MemoryHistory.SAMPLE_INTERVAL.toMinutes() + " * * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public void executor(TaskContext ctx) {
        sweep();
    }

    /**
     * Measures every host whose kernel its shell reaches, then samples every running app that holds memory.
     *
     * AIDEV-NOTE: hosts first: their shell has its own time bound, while a daemon's stats stream answers when it
     * answers, so a wedged daemon may hold the app half of the sweep but never the hosts' readings.
     */
    public static void sweep() {
        HostModel hosts = Models.get(HostModel.class);
        for (Row server : hosts.find().all()) {
            // A quarantined host's answer proves nothing about which machine answered, as on the heartbeat.
            if (server.get(HostModel.QUARANTINED_AT) != null || !HostMemory.measurable(server)) {
                continue;
            }
            HostMemory.Reading reading = HostMemory.measure(server, HostShell.forHost(server));
            if (reading != null) {
                HostMemory.record(server, reading, Now.instant());
                hosts.save(server);
            }
        }
        Duration keep = MemoryHealth.Thresholds.current().longest();
        Set<Integer> running = new HashSet<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_RUNNING))
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null || InstanceCapacity.reservedMbOf(instance) <= 0) {
                continue;
            }
            running.add(id);
            Long used = usedBytesOf(id);
            if (used != null) {
                MemoryHistory.record(id, new MemoryHistory.Sample(Now.instant(), used), keep);
            }
        }
        MemoryHistory.retain(running);
    }

    /** @return the workload's memory use now, null when its driver has no stats lane or it would not answer */
    private static @Nullable Long usedBytesOf(int instanceId) {
        try {
            InstanceService.Resolved resolved = new InstanceService().resolve(instanceId);
            if (!(resolved.runtime() instanceof StatsStreamSupport support)) {
                return null;
            }
            try (StatsFeed feed = support.openStats(resolved.spec().handle())) {
                StatsReading reading = feed.next();
                return reading == null ? null : reading.memoryBytes();
            }
        } catch (IOException | RuntimeException unreadable) {
            Blast.log("MEMORY: could not sample instance", instanceId, "-", unreadable.getMessage());
            return null;
        }
    }
}
