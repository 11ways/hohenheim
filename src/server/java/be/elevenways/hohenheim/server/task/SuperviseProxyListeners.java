package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Minutely proxy-listener supervision: restarts a FAILED or degraded listener under the
 * bounded backoff owned by {@link ProxyServer}, so a transient failure (the Aug 04 2026
 * fd-exhaustion incident) heals without waiting for an unrelated model write to reload.
 */
public class SuperviseProxyListeners extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Supervise proxy listeners";

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("supervise_proxy_listeners");
    }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("supervise_proxy_listeners").withFilter("scope", HohenheimMicrocopy.SCOPE)
            .withFilter("target", "task_label");
    }

    @Override
    public @NonNull SuperviseProxyListeners newTask() {
        return new SuperviseProxyListeners();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.bootAndCron("* * * * *")),
            HohenheimRoles.Role.PROXY);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        ProxyServer proxy = ServerMain.getProxyServer();
        // AIDEV-NOTE: null only in the boot window (the TASKS-stage boot fire can run
        // before ServerMain constructs the proxy) or in tests -- the schedule itself is
        // gated on Role.PROXY, so a role-less node never runs this. The next minutely
        // tick supervises the real instance; a silent no-op here is a one-minute gap,
        // not a lost supervision lane.
        if (proxy == null) return;
        proxy.superviseListeners();
    }
}
