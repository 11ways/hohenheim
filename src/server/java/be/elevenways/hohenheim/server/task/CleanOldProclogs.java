package be.elevenways.hohenheim.server.task;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Deletes process log entries older than the retention period. Runs daily.
 */
public class CleanOldProclogs extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Delete old process logs";

    private static final int RETENTION_DAYS = 30;

    @Override
    public @NonNull CleanOldProclogs newTask() {
        return new CleanOldProclogs();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("30 4 * * *")),
            HohenheimRoles.Role.PROCESSES);
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
     * Delete proclog rows whose last write is past the retention window.
     *
     * AIDEV-NOTE: SAVED_AT, not CREATED_AT -- the CleanOldInstanceLogs rule, because this
     * tier has the same write shape: a proclog row is UPSERTED for the whole life of ONE
     * process (ManagedProcessSiteHandler.persistProclog re-finds it by id on every flush,
     * and CREATED_AT is the process START time). Sweeping by created_at deleted the LIVE
     * row of any process running past the window mid-write; the next flush silently
     * started a fresh row and the history the window promised was gone.
     */
    public static void clean() {
        RetentionSweep.clean("CleanOldProclogs", Models.get(ProclogModel.class),
            ProclogModel.SAVED_AT, RETENTION_DAYS);
    }
}
