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

    /** Delete instance console log rows older than the retention window. */
    public static void clean() {
        RetentionSweep.clean("CleanOldInstanceLogs", Models.get(InstanceLogModel.class),
            InstanceLogModel.CREATED_AT, RETENTION_DAYS);
    }
}
