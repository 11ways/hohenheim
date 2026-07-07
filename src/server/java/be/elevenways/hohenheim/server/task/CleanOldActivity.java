package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Deletes activity-log entries older than the retention period. Runs daily.
 */
public class CleanOldActivity extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Delete old activity entries";

    private static final int RETENTION_DAYS = 90;

    public static List<ScheduleDeclaration> defaultSchedules() {
        return List.of(ScheduleDeclaration.fallback("0 5 * * *"));
    }

    @Override
    public void executor(TaskContext ctx) {
        clean();
    }

    /** Delete activity rows older than the retention window; logs, never throws. */
    public static void clean() {
        try {
            int deleted = ActivityLog.prune(Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS));
            if (deleted > 0) {
                Blast.log("TASK: CleanOldActivity removed", deleted, "entries older than",
                    RETENTION_DAYS, "days");
            }
        } catch (Exception e) {
            Blast.log("TASK: CleanOldActivity failed:", e.getMessage());
        }
    }
}
