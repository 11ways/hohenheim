package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.traffic.AppDestinations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The destination reader: one listing of each host's policy table per minute, its flow counters turned into where
 * each app's outgoing connections went (AppDestinations), off every request.
 *
 * AIDEV-NOTE: the minute is the finest step of the totals, and each host costs one listing; the hosts it reads are
 * the ones the isolation sweep planned, which also adds and removes their accounting chains.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class ReadAppDestinations extends ScheduledTask {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("read_app_destinations");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull ReadAppDestinations newTask() {
        return new ReadAppDestinations();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(List.of(ScheduleDeclaration.fallback("* * * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public void executor(TaskContext ctx) {
        if (AppDestinations.readAll() == 0) {
            ctx.reportIdle();
        }
    }
}
