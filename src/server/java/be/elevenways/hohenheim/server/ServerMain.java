package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.stats.StatsCollector;
import be.elevenways.hohenheim.server.task.CleanExpiredSessions;
import be.elevenways.hohenheim.server.task.CleanOldAuditLogs;
import be.elevenways.hohenheim.server.task.CleanOldProclogs;
import be.elevenways.hohenheim.server.task.TaskScheduler;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.task.UpdateNodeVersions;
import be.elevenways.hohenheim.server.task.UpdateSystemUsers;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.ServerZenitRuntime;

/**
 * Server entry point for Hohenheim.
 */
public class ServerMain {

    private static ProxyServer proxyServer;
    private static StatsCollector statsCollector;

    public static void main(String[] args) {
        // Register site types first (before SiteModel's RegistryEnumField is used)
        SiteTypes.register();

        // init() loads ServerSettings from default.dry / local.dry, fires the
        // BlastAutoLoadInit force-loader (materializing HohenheimSettings'
        // @ZenitAutoLoad groups), and kicks off the boot stages.
        ServerZenitRuntime.init();

        // Load Hohenheim's own settings from the same sources. Its context roots
        // at Zenit.SETTINGS (groups are top-level), so it loads them the way
        // ServerSettings does. Boot stages run async and read no Hohenheim
        // setting; the manual startup below sees configured values.
        HohenheimSettings.VALUES.loadFrom(ServerZenitRuntime.defaultSettingsSources());

        HohenheimEndpoints.init();
        HohenheimDatabase.init();

        // main() does the HTTP-server side of startup; init() is idempotent.
        ServerZenitRuntime.main(args);
        Zenit.getHawkeye().setClientScriptLocation("/hohenheim.js");

        // Register handlers after the runtime is ready
        HohenheimHandlers.init();

        statsCollector = new StatsCollector();
        statsCollector.start();

        proxyServer = new ProxyServer();
        proxyServer.start();

        // Start scheduled maintenance tasks
        TaskScheduler taskScheduler = new TaskScheduler();
        var ipTask = new UpdateSystemIpAddresses();
        var userTask = new UpdateSystemUsers();
        var nodeTask = new UpdateNodeVersions();

        // Run discovery tasks immediately, then hourly
        ipTask.run();
        userTask.run();
        nodeTask.run();
        taskScheduler.schedule("UpdateSystemIpAddresses", ipTask, 60, 60);
        taskScheduler.schedule("UpdateSystemUsers", userTask, 60, 60);
        taskScheduler.schedule("UpdateNodeVersions", nodeTask, 60, 60);

        // Cleanup tasks: run every 6 hours after a 5-minute initial delay
        taskScheduler.schedule("CleanExpiredSessions", new CleanExpiredSessions(), 5, 360);
        taskScheduler.schedule("CleanOldAuditLogs", new CleanOldAuditLogs(), 5, 360);
        taskScheduler.schedule("CleanOldProclogs", new CleanOldProclogs(), 5, 360);
    }

    public static ProxyServer getProxyServer() {
        return proxyServer;
    }

    public static StatsCollector getStatsCollector() {
        return statsCollector;
    }
}
