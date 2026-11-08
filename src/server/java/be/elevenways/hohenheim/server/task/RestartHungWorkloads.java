package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.instance.HungWorkloads;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Restarts every workload a site serves that keeps connecting without answering ({@link HungWorkloads}); at this
 * cadence a hang is restarted within about six minutes.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class RestartHungWorkloads extends ScheduledTask {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("restart_hung_workloads");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull RestartHungWorkloads newTask() {
        return new RestartHungWorkloads();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("*/2 * * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public void executor(TaskContext ctx) {
        List<Integer> restarted = new HungWorkloads().sweep();
        if (!restarted.isEmpty()) {
            ctx.report("Restarted " + restarted.size() + " workloads that stopped answering their sites");
        }
    }
}
