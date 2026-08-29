package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.dns.DnsDelegationHealth;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Compares every primary zone's delegation at the parent with the apex NS RRset it
 * serves and asks each delegated server for the zone, hourly (the registrar side changes
 * on a human timescale, and each check queries third-party servers).
 */
public class CheckDnsDelegations extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Check DNS delegations";

    @Override
    public @NonNull CheckDnsDelegations newTask() {
        return new CheckDnsDelegations();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("23 * * * *")),
            HohenheimRoles.Role.DNS);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        DnsDelegationHealth.checkAll();
    }
}
