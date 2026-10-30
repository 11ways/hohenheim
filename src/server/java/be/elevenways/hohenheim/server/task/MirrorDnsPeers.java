package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.dns.DnsPeerMirrors;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Brings every DNS peer that mirrors all zones up to date, so a peer that was unreachable when a zone changed, or a
 * zone enabled on a schedule, still ends up mirrored.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class MirrorDnsPeers extends ScheduledTask {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("mirror_dns_peers");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull MirrorDnsPeers newTask() {
        return new MirrorDnsPeers();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("*/10 * * * *")),
            HohenheimRoles.Role.DNS);
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public void executor(TaskContext ctx) {
        DnsPeerMirrors.mirrorAll();
    }
}
