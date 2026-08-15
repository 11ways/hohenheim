package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.server.cli.OfflineBoot;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.cms.ManagePanel;
import be.elevenways.hohenheim.server.database.TenantDatabases;
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
import be.elevenways.hohenheim.server.docker.SiteReleases;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessCapacity;
import be.elevenways.hohenheim.server.process.ProcessInfrastructure;
import be.elevenways.hohenheim.server.process.ProcessReaper;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.security.KnownPermission;
import be.elevenways.zenit.common.security.KnownPermissions;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.auth.server.AuthRegistry;
import be.elevenways.zenit.auth.server.AuthRequirement;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusIdentityProvider;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.task.TaskRuntime;
import be.elevenways.zenit.server.task.TaskService;

/**
 * Server entry point for Hohenheim.
 */
public class ServerMain {

    private static ProxyServer proxyServer;
    private static DnsServer dnsServer;
    private static SecondaryZoneService secondaryZoneService;

    public static void main(String[] args) {
        if (runCommandLineOnly(args)) {
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
        HohenheimChannels.init();
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
            // ORDER: reap, then sweep, then reset the memory bookings. A child that
            // outlived the previous controller still holds its port and its host-memory
            // charge; both may only be handed back once it is actually dead. Reaping
            // FIRST is what turns the sweep's "still bound, kill it by hand" case back
            // into an ordinary release, and what makes the process-capacity reset honest
            // instead of a way to over-book a host that is still full.
            ProcessReaper.reapOrphans();
            allocator.sweepPreviousControllerClaims();
            ProcessCapacity.resetOn(ServerModel.localServerId());
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
            // The documented migration of the Phase 7 stack lowering: a stack whose
            // services own no instances is re-deployed under the contract onto its
            // EXISTING volumes, and its pre-lowering containers are retired one by one
            // (only where the daemon still attributes them to that stack). Idempotent.
            JobRunner.startVirtualThread(StackInstances::adoptExisting);
        } else {
            roleSkip(HohenheimRoles.Role.STACKS,
                "stack runtime not started, interrupted-deploy sweep skipped");
        }
        if (HohenheimRoles.enabled(HohenheimRoles.Role.DATABASES)) {
            // The documented migration of the Phase 7 database lowering: a database
            // record predating it owns no instance, so it gets one and is re-deployed
            // onto its EXISTING data volume rather than being abandoned with a running
            // container nothing tracks. Idempotent, so it is a fast no-op once adopted.
            // Virtual thread: it does live daemon work per record.
            JobRunner.startVirtualThread(DatabaseInstances::adoptExisting);
        } else {
            roleSkip(HohenheimRoles.Role.DATABASES,
                "managed-database adoption skipped, no databases run here");
        }
        if (HohenheimRoles.enabled(HohenheimRoles.Role.INSTANCES)) {
            // A controller killed mid-migration leaves a record MIGRATING with its
            // destination pointer set; the settle decides rollback vs completion from
            // daemon attribution so ownership can never stay split. Virtual thread:
            // it does live daemon work on up to two hosts per record.
            JobRunner.startVirtualThread(InstanceMigrations::recoverInterrupted);
            // A controller killed mid-upload leaves a backup row UPLOADING forever
            // (invisible to the dashboard, never swept) and possibly a committed
            // artifact; killed mid-capture it leaves a FAILED snapshot row whose
            // payload nothing reclaims. Both settles fence on the process start time,
            // so rows written by THIS process (live operations) are never touched.
            // Virtual threads: both do target/daemon I/O.
            JobRunner.startVirtualThread(InstanceBackups::recoverInterrupted);
            JobRunner.startVirtualThread(() -> new InstanceSnapshots().recoverInterrupted());
            // A controller killed mid-capture or mid-restore leaves the INSTANCE ROW
            // stamped capturing/restoring, and only the in-process outcome paths clear
            // those -- so the record refused deploy, stop, backup and snapshot forever
            // (destroy was the only escape). Same process-start fence as the two settles
            // above; the capture lane settles from daemon truth, an interrupted restore
            // settles to error because the payload may be half-written. Virtual thread:
            // it asks the daemon per record.
            JobRunner.startVirtualThread(InstanceService::recoverInterrupted);
        } else {
            roleSkip(HohenheimRoles.Role.INSTANCES,
                "interrupted-migration settle skipped, no instances run here");
        }
        if (HohenheimRoles.enabled(HohenheimRoles.Role.PROXY)) {
            // A restart mid-release leaves a durable operation claiming to be in
            // flight: pre-switch ones lose their candidate (the prior release was
            // never replaced), a half-flipped switch is completed and a lost drain is
            // finished. Virtual thread for the same reason as the stack sweep: it
            // does live daemon work and must never hold the listeners off the wire.
            JobRunner.startVirtualThread(SiteReleases::recoverInterrupted);
            // Preview lifetimes are one-shot record schedules: a deadline missed
            // while down fires at the framework sweeper's next pass, no boot sweep.
        } else {
            roleSkip(HohenheimRoles.Role.PROXY,
                "release recovery skipped, no sites are served here");
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

        // The secret-normalization hooks (site api keys, reserved env, enable
        // invariant) install via the discovered HohenheimWriteHooks ZenitModule
        // at the MODULES stage inside ServerZenitRuntime.main() above, BEFORE
        // the HTTP server binds; the one-time sweep of pre-existing plaintext
        // rides SiteApiKeySeeder at the SEED stage. (Dyndns tokens live hashed
        // in dns_dyndns_credentials; DynamicDnsService.mintFor is the one writer.)

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

    /**
     * Every command-line-only lane, in dispatch order; nothing here boots a server.
     *
     * Migrate-only invocation (zenit-dev's migration step): settings + datasource +
     * migrations, then exit WITHOUT booting any server. Without this early path the
     * migration step used to boot a full server (both listeners) that only a kill -9
     * timeout stopped.
     *
     * AIDEV-NOTE: migrations must run through ServerZenitRuntime, not
     * HohenheimDatabase.init(): the framework prints the sentinels zenit-dev's migration
     * preflight reads. A hand-rolled branch printed neither, so every successful run was
     * reported as "exited without proving completion" and the server never started.
     *
     * AIDEV-NOTE: the flag SCAN is the framework's too, and that is why there is no
     * `args.contains("--run-migrations")` guard around that call any more. The entry point
     * also owns --repair-migration-checksums, so the hand-rolled guard silently hid that
     * lane on this host; the call itself returns false without opening anything when
     * neither flag is present, and the supplier is lazy.
     *
     * AIDEV-NOTE: this is a METHOD rather than inline main() code so a test can drive the
     * real dispatch without booting the world. The wiring is the thing that was missing --
     * the framework registry, its command and their tests all existed while no application
     * called them -- so it needs a test of its own, and one that goes through main() cannot
     * be written safely.
     *
     * @return true when a command ran and {@code main} must stop
     */
    public static boolean runCommandLineOnly(String[] args) {
        if (ServerZenitRuntime.runMigrationsIfRequested(args,
                ServerMain::openDatabaseForCommandLine)) {
            return true;
        }

        // The break-glass lane: control-plane archives, field-encryption key rotation and
        // zenit-auth's --set-password all run here with the datasource open and no HTTP
        // boot; --offline-help lists everything discovered.
        return OfflineBoot.runIfRequested(args);
    }

    /**
     * Settings, grant declarations and the datasource for a CLI-only invocation.
     *
     * The framework's migration entry point closes what this returns, and it is only
     * invoked when a migration flag is actually present -- a normal boot opens nothing here.
     */
    private static @NonNull SqlDatasource openDatabaseForCommandLine() {
        HohenheimSettingsFiles.load();
        HohenheimAccess.declareGrantableModels();
        return HohenheimDatabase.openDatasource();
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
            // AIDEV-NOTE: DELEGABLE, deliberately (owner's call, 2026-08-15). Both of these
            // used to be declared nonDelegable so that a holder could not grant their own
            // authority onward. That is not the model this product wants: a permission is a
            // leaf, and holding it means holding it -- including the ability to grant it,
            // which is what an admin being an admin means. Whoever may administer grants may
            // therefore mint a peer admin. Do not reintroduce the asymmetry without the
            // owner saying so; the mechanism still exists upstream (auth.grants.manage) for
            // permissions that genuinely need it.
            KnownPermission.of(
                HohenheimPanel.ACCESS.value(),
                Microcopy.of("hohenheim_admin_access").withFilter("scope", "permission")),
            KnownPermission.of(
                ManagePanel.ACCESS.value(),
                Microcopy.of("hohenheim_manage_access").withFilter("scope", "permission")),
            // Every-site authority WITHOUT the admin permission (the walk's type-level row on
            // SiteModel). Delegable for the reason above, and it could not be otherwise once
            // admin.access is: guarding the lesser authority while the greater one flows
            // freely protects nothing.
            KnownPermission.of(
                HohenheimAccess.SITES_MANAGE_ALL.value(),
                Microcopy.of("hohenheim_sites_manage_all").withFilter("scope", "permission")),
            // Install media on a host: publishing ISOs onto its storage and removing them.
            // Its own permission on purpose (HohenheimSources.MEDIA_MANAGE says why), which
            // is exactly why it must appear HERE -- an enforced permission missing from this
            // corpus is a permission no admin can find to grant.
            KnownPermission.of(
                HohenheimSources.MEDIA_MANAGE.value(),
                Microcopy.of("hohenheim_media_manage").withFilter("scope", "permission")),
            // Tenant self-service creation: eligibility only. It provisions a workload on
            // an operator's iron, and the per-owner quota is what bounds how many.
            KnownPermission.of(
                HohenheimAccess.INSTANCES_CREATE.value(),
                Microcopy.of("hohenheim_instances_create").withFilter("scope", "permission")),
            // The managed-database sibling of INSTANCES_CREATE, and registered for the
            // same reason: this block IS the grants editor's autocomplete corpus
            // (KnownPermissions.all()), so an enforced permission missing from it is a
            // permission no admin can find. PermissionVocabularyTest is the guard.
            KnownPermission.of(
                TenantDatabases.DATABASES_CREATE.value(),
                Microcopy.of("hohenheim_databases_create").withFilter("scope", "permission")));
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
