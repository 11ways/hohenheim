package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Deletes activity-log entries older than the retention period. Runs daily.
 */
public class CleanOldActivity extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Delete old activity entries";

    private static final int RETENTION_DAYS = 90;

    @Override
    public @NonNull CleanOldActivity newTask() {
        return new CleanOldActivity();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return List.of(ScheduleDeclaration.fallback("0 5 * * *"));
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
     * Delete activity rows older than the retention window.
     *
     * @throws RuntimeException on failure -- recorded by the task system as FAILED
     */
    public static void clean() {
        int deleted = ActivityLog.prune(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS));
        if (deleted > 0) {
            Blast.log("TASK: CleanOldActivity removed", deleted, "entries older than",
                RETENTION_DAYS, "days");
        }
    }
}
