package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.RootDiskUsageSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The OBSERVED-disk sweeper: asks every running instance's driver how much of its root
 * disk is actually occupied and stamps the answer on the record, so the dashboard reads a
 * stored fact instead of dialling every daemon per render.
 *
 * AIDEV-NOTE: the signal is HONEST PER TIER by construction, and that is the whole design.
 * Only a driver that implements {@link RootDiskUsageSupport} is asked, so an Incus workload
 * (btrfs qgroup: a real, enforced ceiling and a real usage figure) gets numbers, and a
 * Docker workload gets NOTHING -- its columns stay null. Docker accepts
 * {@code --storage-opt size=2G} on overlayfs and enforces nothing (measured: 2.5GB written
 * into a 2G declaration), so a "disk 90% full" item computed there would be an alarm about
 * a limit that does not exist. Null is the honest answer and the collector reads it as
 * "no news", never as zero.
 *
 * AIDEV-NOTE: the stamp uses {@code updateAll} ON PURPOSE. It is hook-free, so an
 * observation cannot trip the capacity/quota write hooks and re-book anything -- writing an
 * observation must never be able to change an instance's charge.
 */
public class ObserveInstanceDisk extends ScheduledTask {

    public static final String STATIC_DESCRIPTION =
        "Observe instance root-disk usage on the drivers that can measure it";

    /** One instance's outcome; MEASURED only where a driver really answered. */
    public record Observation(int instanceId, boolean measured, long usedBytes,
                              long limitBytes) {
    }

    @Override
    public @NonNull ObserveInstanceDisk newTask() {
        return new ObserveInstanceDisk();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // Every ten minutes: disk fills gradually, and each observation is one cheap state
        // read per RUNNING instance on a driver that supports it.
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("*/10 * * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        sweep();
    }

    /** Observe and stamp every running instance whose driver can measure its root disk. */
    public static @NonNull List<Observation> sweep() {
        List<Observation> observations = new ArrayList<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull())
                .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_RUNNING))
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null) {
                continue;
            }
            Observation observation = observe(id);
            if (observation != null) {
                observations.add(observation);
            }
        }
        return List.copyOf(observations);
    }

    /**
     * Observe ONE instance and stamp it.
     *
     * @return the observation, or null when this instance's driver cannot measure at all
     *         (no capability) -- distinct from a driver that could be asked and failed
     */
    public static @Nullable Observation observe(int instanceId) {
        InstanceService.Resolved resolved;
        try {
            resolved = new InstanceService().resolve(instanceId);
        } catch (RuntimeException unresolvable) {
            return null;
        }
        if (!(resolved.runtime() instanceof RootDiskUsageSupport support)) {
            return null;   // the tier cannot measure: leave the columns null
        }
        RootDiskUsageSupport.DiskUsage usage;
        try {
            usage = support.rootDiskUsage(resolved.spec());
        } catch (IOException unreadable) {
            Blast.log("DISK: could not observe the root disk of instance", instanceId,
                "-", unreadable.getMessage());
            return new Observation(instanceId, false, 0, 0);
        }
        if (usage == null) {
            return new Observation(instanceId, false, 0, 0);
        }
        Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.DISK_USED_BYTES, usage.usedBytes())
            .assign(InstanceModel.DISK_LIMIT_BYTES, usage.limitBytes())
            .assign(InstanceModel.DISK_OBSERVED_AT, Instant.now())
            .updateAll();
        return new Observation(instanceId, true, usage.usedBytes(), usage.limitBytes());
    }
}
