package be.elevenways.hohenheim.server.task;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;

import java.util.List;

/**
 * Deletes process log entries older than the retention period. Runs daily.
 */
public class CleanOldProclogs extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Delete old process logs";

    private static final int RETENTION_DAYS = 30;

    public static List<ScheduleDeclaration> defaultSchedules() {
        return List.of(ScheduleDeclaration.fallback("30 4 * * *"));
    }

    @Override
    public void executor(TaskContext ctx) {
        clean();
    }

    /** Delete proclog rows older than the retention window. */
    public static void clean() {
        RetentionSweep.clean("CleanOldProclogs", Models.get(ProclogModel.class),
            ProclogModel.CREATED_AT, RETENTION_DAYS);
    }
}
