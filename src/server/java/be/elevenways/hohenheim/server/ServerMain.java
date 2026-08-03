package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.dns.DnsNotifier;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.SecondaryZoneService;
import be.elevenways.hohenheim.server.proxy.ProxyReloadHooks;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.ProteusRealmSuggestions;
import be.elevenways.hohenheim.server.auth.SiteAuthProviders;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessInfrastructure;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.security.KnownPermission;
import be.elevenways.zenit.common.security.KnownPermissions;
import be.elevenways.zenit.cms.server.page.ResourcePageEndpoints;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.auth.server.AuthRegistry;
import be.elevenways.zenit.auth.server.AuthRequirement;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusIdentityProvider;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.zenit.server.task.TaskRuntime;
import be.elevenways.zenit.server.task.TaskService;

import java.util.List;

/**
 * Server entry point for Hohenheim.
 */
public class ServerMain {

    private static ProxyServer proxyServer;
    private static DnsServer dnsServer;
    private static SecondaryZoneService secondaryZoneService;

    public static void main(String[] args) {
        // Migrate-only invocation (zenit-dev's migration step): settings +
        // datasource + migrations, then exit WITHOUT booting any server.
        // Without this early path the migration step used to boot a full
        // server (both listeners) that only a kill -9 timeout stopped.
        // AIDEV-NOTE: it must run through ServerZenitRuntime, not HohenheimDatabase.init():
        // the framework prints the sentinels zenit-dev's migration preflight reads. A
        // hand-rolled branch printed neither, so every successful run was reported as
        // "exited without proving completion" and the server never started.
        // Backup/restore-only invocations for control-plane recovery archives (database +
        // keyring as one verified unit), exiting WITHOUT booting. Restore is OFFLINE BY
        // DESIGN -- it replaces the SQLite file, so it must never run while a server has it
        // open; the archive is fully checksum-verified before either half is touched. Both
        // load the zenit settings chain too, so a database.encryption.key_file override in
        // settings/local.dry is honored exactly like a real boot.
        if (args != null) {
            List<String> argList = java.util.Arrays.asList(args);

            if (argList.contains("--backup-control-plane")) {
                HohenheimSettingsFiles.load();
                ServerSettings.VALUES.loadFrom(ServerZenitRuntime.defaultSettingsSources());
                var datasource = HohenheimDatabase.openDatasource();
                try {
                    ControlPlaneBackups.backupNow();
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException("Control-plane backup failed", error);
                } finally {
                    datasource.close();
                }
                return;
            }

            int restoreFlag = argList.indexOf("--restore-control-plane");
            if (restoreFlag >= 0) {
                if (restoreFlag + 1 >= args.length) {
                    throw new IllegalArgumentException(
                        "--restore-control-plane needs the archive path as its next argument");
                }
                HohenheimSettingsFiles.load();
                ServerSettings.VALUES.loadFrom(ServerZenitRuntime.defaultSettingsSources());
                ControlPlaneBackups.restore(java.nio.file.Path.of(args[restoreFlag + 1]));
                return;
            }
        }

        if (args != null && java.util.Arrays.asList(args).contains("--run-migrations")) {
            HohenheimSettingsFiles.load();
            HohenheimAccess.declareGrantableModels();
            ServerZenitRuntime.runMigrationsIfRequested(args,
                HohenheimDatabase::openDatasource,
                HohenheimDatabase::acknowledgeRetiredVersions);
            return;
        }

        // Load Hohenheim's own settings (settings/hohenheim.dry + HOHENHEIM__*
        // env). The context roots at the hohenheim group, so file keys keep the
        // flat proxy/ssl/... shape. Also captures the HohenheimRoles snapshot,
        // which every role gate below reads. BEFORE SiteTypes.boot(): the
        // process-monitor thread is a roles.processes side effect and nothing
        // between the old order and this one read a setting.
        HohenheimSettingsFiles.load();

        // Site types and auth-provider types self-register through compile-time
        // discovery (BlastAutoLoadInit); only the shared process infrastructure
        // needs an explicit boot before the proxy loads its routes -- and only
        // on a node that actually runs managed processes.
        if (HohenheimRoles.enabled(HohenheimRoles.Role.PROCESSES)) {
            SiteTypes.boot();
        } else {
            roleSkip(HohenheimRoles.Role.PROCESSES,
                "process monitor and port/socket allocators not started");
        }

        HohenheimEndpoints.init();
        // Force-load the zenit-cms panel routes (all /{panel}/... endpoints).
        Object cmsRoutes = ResourcePageEndpoints.LIST;
        // AIDEV-NOTE: BEFORE the migrations, not with the rest of the auth wiring. The
        // grant declarations carry the per-model LIVENESS definition, and zenit-auth's
        // one-time orphan purge (M009) is a migration: a declaration that lands afterwards
        // leaves that purge judging a hand-stamped soft delete as a live record. Pure
        // registry writes, so nothing here needs the database.
        HohenheimAccess.declareGrantableModels();
        HohenheimDatabase.init();   // also registers the SQLite datasource as the framework default

        // Managed-process port claims outlive the JVM that made them, so a previous run's
        // rows are reconciled HERE: after the database exists (SiteTypes.boot() runs long
        // before it) and before anything can load a site and allocate. A port still bound
        // by a child that outlived its controller keeps its claim -- see the sweep's note.
        PortAllocator allocator = ProcessInfrastructure.portAllocator();
        if (allocator != null) {
            allocator.sweepPreviousControllerClaims();
        }

        // Install zenit-auth (session store, CSRF, middleware, /login + /setup + /account + /admin).
        // Password login is native; Proteus SSO is added below when configured.
        ZenitAuth.init(HohenheimDatabase.datasource());
        // The users/roles resources live in HohenheimPanel's security group;
        // zenit-auth's own default panel would be a second UI over the same records.
        AuthSettings.VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
        installAuthBaselines();
        registerProteusIfConfigured();

        // main() does the HTTP-server side of startup; init() is idempotent.
        // Everything an incoming request needs -- client script location, endpoint
        // handlers, panels, the security engine -- is installed by the discovered
        // HohenheimHostWiring module at the MODULES stage, which completes before
        // this call binds the listener. Nothing request-facing may be added below.
        ServerZenitRuntime.main(args);

        // Stacks: prove the Docker daemon is reachable, loudly, before anything
        // trusts it (a missing socket used to degrade completely silently); a
        // node without the stacks role never constructs a DockerClient here.
        DockerHealth.probeAtBoot();

        if (HohenheimRoles.enabled(HohenheimRoles.Role.STACKS)) {
            // A restart mid-deploy leaves stacks claiming "deploying", which would
            // disable their monitoring forever. Swept on a virtual thread: the sweep
            // does live Docker inspects (60s timeout each), and a wedged daemon --
            // a common reason for the restart -- must never hold the proxy, DNS and
            // security engine off the wire for it. A monitor tick racing the sweep
            // is safe: both serialize on the per-stack worker.
            JobRunner.startVirtualThread(() -> StackRuntime.get().resetInterruptedDeploys());
        } else {
            roleSkip(HohenheimRoles.Role.STACKS,
                "stack runtime not started, interrupted-deploy sweep skipped");
        }
        installShutdownHook();
        if (HohenheimRoles.enabled(HohenheimRoles.Role.FIREWALL)) {
            // The manager owns its own platform-thread lifecycle lane; never hold
            // HTTP/proxy/DNS startup on its readiness.
            SpamserviceManager.get().boot();
        } else {
            roleSkip(HohenheimRoles.Role.FIREWALL, "spamservice manager not booted");
        }

        if (HohenheimRoles.enabled(HohenheimRoles.Role.PROXY)) {
            proxyServer = new ProxyServer();
            proxyServer.start();
            ProxyReloadHooks.install();
        } else {
            // ABSENT, not FAILED: getProxyServer() stays null, so status surfaces
            // show "not part of this install" instead of a false-red bind failure.
            roleSkip(HohenheimRoles.Role.PROXY, "proxy listeners not started");
        }

        // The secret-normalization hooks (site api keys, dyndns tokens, reserved
        // env, enable invariant) install via the discovered HohenheimWriteHooks
        // ZenitModule at the MODULES stage inside ServerZenitRuntime.main() above,
        // BEFORE the HTTP server binds; the one-time sweeps of pre-existing
        // plaintext ride SiteApiKeySeeder/DyndnsTokenSeeder at the SEED stage.

        if (HohenheimRoles.enabled(HohenheimRoles.Role.DNS)) {
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
        } else {
            roleSkip(HohenheimRoles.Role.DNS,
                "zone store, federation and DNS listeners not started");
        }
    }

    /** A skipped role must never be silent: name the role and what did not start. */
    private static void roleSkip(HohenheimRoles.@NonNull Role role, @NonNull String skipped) {
        Blast.slog("hohenheim.role_disabled", java.util.Map.of(
            "role", role.token(),
            "skipped", skipped));
    }

    /** Registers cleanup before the first managed child can start. */
    private static void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (proxyServer != null) proxyServer.stop();
            if (dnsServer != null) dnsServer.stop();
            if (secondaryZoneService != null) secondaryZoneService.stop();
            SpamserviceManager.get().shutdown();
            ProcessInfrastructure.shutdown();
            // Hand the host leases back so a successor controller does not have to
            // wait out the TTL; a crash still recovers through expiry.
            HostLeases.production().releaseAll();
        }, "hohenheim-shutdown"));
    }

    // baseline("/") is a catch-all (zenit-auth 620125d): every admin path requires login except
    // the public prefixes below and zenit-auth's own login/setup/asset paths. Git webhooks are
    // served by the PROXY listener (SiteDispatcher), not this server, so they stay reachable.
    public static void installAuthBaselines() {
        AuthRegistry.registerPublicPrefix("/api/health");
        // The dyndns endpoint authenticates by update token in the request itself,
        // not a session; the handler refuses anything the token does not unlock.
        AuthRegistry.registerPublicPrefix("/nic/update");
        AuthRegistry.baseline("/", AuthRequirement.requiresLogin());
        // declareGrantableModels() already ran, before the migrations -- see main().
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
        return TaskRuntime.service();
    }

    public static DnsServer getDnsServer() {
        return dnsServer;
    }
}
