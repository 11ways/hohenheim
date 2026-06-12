package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.stats.StatsCollector;
import be.elevenways.zenit.auth.server.AuthRegistry;
import be.elevenways.zenit.auth.server.AuthRequirement;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusIdentityProvider;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.task.TaskBootstrap;
import be.elevenways.zenit.server.task.TaskService;

/**
 * Server entry point for Hohenheim.
 */
public class ServerMain {

    private static ProxyServer proxyServer;
    private static StatsCollector statsCollector;
    private static TaskService taskService;

    public static void main(String[] args) {
        // Register site types and auth-provider types first (before the models'
        // RegistryEnumFields are used and before the proxy loads its routes).
        SiteTypes.register();
        SiteAuthProviders.register();

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
        HohenheimDatabase.init();   // also registers the SQLite datasource as the framework default

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

        // Scheduled maintenance via zenit's TaskService: discovery tasks (IP / users / node
        // versions) declare BOOT_AND_CRON so they run once now and hourly; cleanup and backup
        // tasks declare daily FALLBACK schedules. Each task's schedule lives on the task class
        // (defaultSchedules()) and is reconciled into system_task on boot. (Session expiry is
        // owned by zenit-auth's session store.)
        taskService = TaskBootstrap.start(
            HohenheimDatabase.datasource(), "be.elevenways.hohenheim.server.task");
    }

    // baseline("/") is a catch-all (zenit-auth 620125d): every admin path requires login except
    // the public prefixes below and zenit-auth's own login/setup/asset paths. Git webhooks are
    // served by the PROXY listener (SiteDispatcher), not this server, so they stay reachable.
    public static void installAuthBaselines() {
        AuthRegistry.registerPublicPrefix("/api/health");
        AuthRegistry.baseline("/", AuthRequirement.requiresLogin());
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

    public static TaskService getTaskService() {
        return taskService;
    }
}
