package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.notification.SiteOutages;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Sweeps the sites apps serve once a minute for an app that stopped answering ({@link SiteOutages}); at this cadence
 * its alert arrives two to three minutes after it stopped.
 *
 * AIDEV-NOTE: a PROXY-role task: the reading is the running proxy's own verdict, which only a process running the
 * proxy holds; anywhere else every site would read unknown and the sweep would only cost queries.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class WatchSiteOutages extends ScheduledTask {

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("watch_site_outages");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull WatchSiteOutages newTask() {
        return new WatchSiteOutages();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("* * * * *")),
            HohenheimRoles.Role.PROXY);
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public void executor(TaskContext ctx) {
        ProxyServer proxy = ServerMain.getProxyServer();
        SiteOutages.sweep(proxy == null ? null : proxy.getDispatcher());
    }
}
