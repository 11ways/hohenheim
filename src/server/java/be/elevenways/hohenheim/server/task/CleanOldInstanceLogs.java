package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.InstanceLogModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Deletes stored instance console episodes past the retention window. Runs daily -- the
 * SAME rule and the same sweeper as {@link CleanOldProclogs}, so the two log tiers cannot
 * drift into two retention policies.
 */
public class CleanOldInstanceLogs extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Delete old instance console logs";

    private static final int RETENTION_DAYS = 30;

    @Override
    public @NonNull CleanOldInstanceLogs newTask() {
        return new CleanOldInstanceLogs();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("40 4 * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        clean();
    }

    /**
     * Delete instance console log rows whose last write is past the retention window.
     *
     * AIDEV-NOTE: SAVED_AT, not CREATED_AT, and this is the one place the shared sweeper's
     * usual column is wrong. An instance log row is UPSERTED for the whole life of ONE
     * console episode (InstanceConsoleLogs.sinkFor), so on a workload that has been
     * streaming for a month CREATED_AT is the age of the EPISODE, never the age of its
     * text: sweeping by it deleted a row still being written to, and the sink's next flush
     * silently started a new row -- losing the history the retention window was supposed to
     * still be holding. SAVED_AT retires an episode 30 days after its LAST line, which is
     * what "logs older than 30 days" means for both a finished and a live episode.
     */
    public static void clean() {
        RetentionSweep.clean("CleanOldInstanceLogs", Models.get(InstanceLogModel.class),
            InstanceLogModel.SAVED_AT, RETENTION_DAYS);
    }
}
