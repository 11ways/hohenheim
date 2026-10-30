package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Joins every database or workload container missing from its link network back to it, so an app that lost its
 * database through a change outside the deploy lanes resolves it again within minutes (InstanceDatabaseNetworks).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class RepairDatabaseLinks extends ScheduledTask {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("repair_database_links");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull RepairDatabaseLinks newTask() {
        return new RepairDatabaseLinks();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("*/5 * * * *")),
            HohenheimRoles.Role.DATABASES);
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public void executor(TaskContext ctx) {
        int repaired = InstanceDatabaseNetworks.repairAll();
        if (repaired > 0) {
            ctx.report("Rejoined " + repaired + " containers to the database link networks they were missing from");
        }
    }
}
