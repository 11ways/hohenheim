package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.dns.DnsSecondaryFreshness;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Asks every linked secondary which serial it serves for each primary zone, so a
 * secondary that silently stopped pulling becomes an attention item and one alert.
 */
public class ProbeDnsSecondaries extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Probe DNS secondaries";

    @Override
    public @NonNull ProbeDnsSecondaries newTask() {
        return new ProbeDnsSecondaries();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("*/5 * * * *")),
            HohenheimRoles.Role.DNS);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        DnsSecondaryFreshness.probeAll();
    }
}
