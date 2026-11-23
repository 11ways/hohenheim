package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Asks every linked Hohenheim, master and managed alike, who answers at its address once a minute, a failing one too
 * ({@link LinkedServers#sweep}).
 *
 * AIDEV-NOTE: node-agnostic on purpose (a bare schedule list, like CheckForeignKeys): a link belongs to the
 * installation, whatever roles this process runs. An installation linked to nothing reports idle.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class ProbeLinkedServers extends ScheduledTask {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("probe_linked_servers");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull ProbeLinkedServers newTask() {
        return new ProbeLinkedServers();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return List.of(ScheduleDeclaration.fallback("* * * * *"));
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public void executor(TaskContext ctx) {
        if (LinkedServers.sweep() == 0) {
            ctx.reportIdle();
        }
    }
}
