package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.preview.PreviewDeployments;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Enforces preview lifetimes: every minute, previews past their stored
 * {@code expires_at} are fully reclaimed (instance, claims, generated domain and DNS
 * rows). The deadline lives in the database, so a missed sweep delays an expiry but
 * can never void it; boot runs the same sweep.
 */
public class PreviewExpirySweep extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Reclaim expired preview deployments";

    @Override
    public @NonNull PreviewExpirySweep newTask() {
        return new PreviewExpirySweep();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("* * * * *")),
            HohenheimRoles.Role.PROXY);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        PreviewDeployments.sweepExpired();
    }
}
