package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.stats.StatsCollector;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.server.task.CleanOldAuditLogs;
import be.elevenways.hohenheim.server.task.CleanOldProclogs;
import be.elevenways.hohenheim.server.task.TaskScheduler;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.task.UpdateNodeVersions;
import be.elevenways.hohenheim.server.task.UpdateSystemUsers;
import be.elevenways.zenit.auth.server.AuthRegistry;
import be.elevenways.zenit.auth.server.AuthRequirement;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusIdentityProvider;
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

        // Install zenit-auth (session store, CSRF, middleware, /login + /setup + /account + /admin).
        // Password login is native; Proteus SSO is added below when configured.
        ZenitAuth.init(HohenheimDatabase.datasource());
        installAuthBaselines();
        registerProteusIfConfigured();

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
        // (session expiry is owned by zenit-auth's session store)
        taskScheduler.schedule("CleanOldAuditLogs", new CleanOldAuditLogs(), 5, 360);
        taskScheduler.schedule("CleanOldProclogs", new CleanOldProclogs(), 5, 360);

        // Managed-database backups: daily, after a 10-minute initial delay
        taskScheduler.schedule("BackupDatabases", new BackupDatabases(), 10, 1440);
    }

    // zenit-auth is not default-deny, so each admin area is gated explicitly; everything not
    // listed (assets, /login, /setup, /api/health) stays public. Public so tests gate identically.
    public static void installAuthBaselines() {
        AuthRegistry.registerPublicPrefix("/api/health");
        for (String prefix : new String[]{
            "/", "/sites", "/certificates", "/access-lists", "/databases",
            "/servers", "/notifications", "/settings", "/audit", "/ws"
        }) {
            AuthRegistry.baseline(prefix, AuthRequirement.requiresLogin());
        }
    }

    // Register the Proteus realm as an SSO option when configured; password login is always available.
    private static void registerProteusIfConfigured() {
        if (!Boolean.TRUE.equals(HohenheimSettings.VALUES.getValue(HohenheimSettings.AuthProteus.ENABLED))) {
            return;
        }
        String endpoint = HohenheimSettings.VALUES.getValue(HohenheimSettings.AuthProteus.ENDPOINT);
        String realmClient = HohenheimSettings.VALUES.getValue(HohenheimSettings.AuthProteus.REALM_CLIENT);
        String accessKey = HohenheimSettings.VALUES.getValue(HohenheimSettings.AuthProteus.ACCESS_KEY);
        if (endpoint == null || endpoint.isBlank() || realmClient == null || realmClient.isBlank()
            || accessKey == null || accessKey.isBlank()) {
            return;
        }
        String authenticator = HohenheimSettings.VALUES.getValue(HohenheimSettings.AuthProteus.AUTHENTICATOR);
        IdentityProviderRegistry.register(
            new ProteusIdentityProvider("proteus", "Proteus",
                new ProteusClient(endpoint, realmClient, accessKey), authenticator, false),
            AutoProvisioningSink.builder().build());
    }

    public static ProxyServer getProxyServer() {
        return proxyServer;
    }

    public static StatsCollector getStatsCollector() {
        return statsCollector;
    }
}
