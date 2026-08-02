package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.docker.DockerReclaim;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Reclaims the disk that superseded images occupy on every server hosting a stack.
 * Every re-pin of a pinned image leaves the previous one behind forever, which on a
 * small host is the difference between working and full.
 */
public class ReclaimDockerImages extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Reclaim disk from superseded Docker images";

    private static final int MIN_AGE_FLOOR_HOURS = 1;

    @Override
    public @NonNull ReclaimDockerImages newTask() {
        return new ReclaimDockerImages();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // Nightly: the sweep costs a couple of daemon round-trips, and an image that
        // became reclaimable during the day is not urgent.
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("23 4 * * *")),
            HohenheimRoles.Role.STACKS);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        if (!Boolean.TRUE.equals(HohenheimSettings.VALUES.getValue(HohenheimSettings.Stacks.RECLAIM_IMAGES))) {
            return;
        }

        Map<String, DockerReclaim.Outcome> outcomes =
            StackRuntime.get().reclaimImages(minimumAge(), includeUnattributed());
        for (Map.Entry<String, DockerReclaim.Outcome> entry : outcomes.entrySet()) {
            DockerReclaim.Outcome outcome = entry.getValue();
            if (outcome.removed() > 0 || outcome.skipped() > 0) {
                Blast.log("DOCKER RECLAIM:", entry.getKey(), "- removed", outcome.removed(),
                    "images,", outcome.megabytes(), "MiB, skipped", outcome.skipped());
            }
        }
    }

    /** Whether unattributable (reference-less) images are swept too; off by default. */
    public static boolean includeUnattributed() {
        return Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Stacks.RECLAIM_UNTRACKED));
    }

    /** The configured age guard, never below the floor: zero would race a live deploy. */
    public static @NonNull Duration minimumAge() {
        Integer hours = HohenheimSettings.VALUES.getValue(HohenheimSettings.Stacks.RECLAIM_MIN_AGE_HOURS);
        int resolved = hours == null ? MIN_AGE_FLOOR_HOURS : Math.max(MIN_AGE_FLOOR_HOURS, hours);
        return Duration.ofHours(resolved);
    }
}
