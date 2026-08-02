package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Refreshes every enabled stack's aggregate status from live container states.
 * Transitions from active into degraded/failed raise a {@code stack_health} alert
 * (inside StackRuntime); Docker's own restart policies do the actual recovering.
 */
public class MonitorStacks extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Monitor managed stack health";

    @Override
    public @NonNull MonitorStacks newTask() {
        return new MonitorStacks();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("*/5 * * * *")),
            HohenheimRoles.Role.STACKS);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        StackRuntime.get().refreshAllStatuses();
    }
}
