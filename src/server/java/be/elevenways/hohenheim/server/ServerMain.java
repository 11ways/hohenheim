package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.hohenheim.server.dns.DnsNotifier;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.SecondaryZoneService;
import be.elevenways.hohenheim.server.proxy.ProxyReloadHooks;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.auth.ProteusRealmSuggestions;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.security.KnownPermission;
import be.elevenways.zenit.common.security.KnownPermissions;
import be.elevenways.zenit.cms.server.page.ResourcePageEndpoints;
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
    private static DnsServer dnsServer;
    private static SecondaryZoneService secondaryZoneService;
    private static TaskService taskService;

    public static void main(String[] args) {
        // Migrate-only invocation (zenit-dev's migration step): settings +
        // datasource + migrations, then exit WITHOUT booting any server.
        // Without this early path the migration step used to boot a full
        // server (both listeners) that only a kill -9 timeout stopped.
        if (args != null && java.util.Arrays.asList(args).contains("--run-migrations")) {
            HohenheimSettingsFiles.load();
            HohenheimDatabase.init();
            return;
        }

        // Site types and auth-provider types self-register through compile-time
        // discovery (BlastAutoLoadInit); only the shared process infrastructure
        // needs an explicit boot before the proxy loads its routes.
        SiteTypes.boot();

        // init() loads ServerSettings from default.dry / local.dry, fires the
        // BlastAutoLoadInit force-loader (materializing HohenheimSettings'
        // @ZenitAutoLoad groups), and kicks off the boot stages.
        ServerZenitRuntime.init();

        // Load Hohenheim's own settings (settings/hohenheim.dry + HOHENHEIM__*
        // env). The context roots at the hohenheim group, so file keys keep the
        // flat proxy/ssl/... shape. Boot stages run async and read no Hohenheim
        // setting; the manual startup below sees configured values.
        HohenheimSettingsFiles.load();

        HohenheimEndpoints.init();
        // Force-load the zenit-cms panel routes (all /{panel}/... endpoints).
        Object cmsRoutes = ResourcePageEndpoints.LIST;
        HohenheimDatabase.init();   // also registers the SQLite datasource as the framework default

        // Install zenit-auth (session store, CSRF, middleware, /login + /setup + /account + /admin).
        // Password login is native; Proteus SSO is added below when configured.
        ZenitAuth.init(HohenheimDatabase.datasource());
        installAuthBaselines();
        registerProteusIfConfigured();

        // main() does the HTTP-server side of startup; init() is idempotent.
        ServerZenitRuntime.main(args);
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        // Register handlers + the admin panel after the runtime is ready
        // (the panel's resources resolve model singletons from the MODELS stage).
        HohenheimHandlers.init();
        new HohenheimPanel();
        new ManagePanel();

        // Native security engine: local event sink, nftables setup + resync,
        // ban-cache warmup. Before the proxy starts so its hot path sees bans.
        HohenheimSecurity.boot();

        proxyServer = new ProxyServer();
        proxyServer.start();
        ProxyReloadHooks.install();

        // The zone store loads regardless of the listeners so zones stay
        // editable (and the internal ACME publisher functional in tests)
        // while the DNS server itself is disabled.
        DnsZoneStore.INSTANCE.reload();

        // Federation: NOTIFY secondaries when a primary zone's serial bumps
        // (admin edits and the ACME publisher both funnel through bumpSerialAndReload),
        // and replicate secondary zones from their primary peers.
        DnsZoneStore.INSTANCE.setOnZoneChanged(DnsNotifier.INSTANCE::notifyZonePeers);
        secondaryZoneService = new SecondaryZoneService(DnsZoneStore.INSTANCE);

        dnsServer = new DnsServer();
        dnsServer.setSecondaryService(secondaryZoneService);
        dnsServer.startIfEnabled();
        secondaryZoneService.start();

        // Reap managed child processes on daemon exit (SIGTERM/SIGINT); without this an
        // abrupt stop leaves every spawned site process running as an orphan.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            proxyServer.stop();
            dnsServer.stop();
            secondaryZoneService.stop();
            NodeSiteType.shutdownSharedInfrastructure();
        }, "hohenheim-shutdown"));

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
        // The dyndns endpoint authenticates by update token in the request itself,
        // not a session; the handler refuses anything the token does not unlock.
        AuthRegistry.registerPublicPrefix("/nic/update");
        // The security ingest endpoint authenticates by zsec_ reporter token in
        // the request itself, not a session.
        AuthRegistry.registerPublicPrefix("/zn/security");
        AuthRegistry.baseline("/", AuthRequirement.requiresLogin());
        KnownPermissions.register("hohenheim",
            KnownPermission.of(
                HohenheimPanel.ACCESS.value(),
                Microcopy.of("hohenheim_admin_access").withFilter("scope", "permission")),
            KnownPermission.of(
                "hohenheim.manage.access",
                Microcopy.of("hohenheim_manage_access").withFilter("scope", "permission")));
        ProteusRealmSuggestions.register();
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

    /** Test/embedding seam: adopt an externally constructed proxy server (null detaches). */
    public static void adoptProxyServer(ProxyServer server) {
        proxyServer = server;
    }

    /** Test/embedding seam: adopt an externally constructed DNS server (null detaches). */
    public static void adoptDnsServer(DnsServer server) {
        dnsServer = server;
    }

    public static TaskService getTaskService() {
        return taskService;
    }

    public static DnsServer getDnsServer() {
        return dnsServer;
    }
}
