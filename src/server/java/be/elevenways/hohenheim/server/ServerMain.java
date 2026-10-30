package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.application.ArtifactDeploys;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.auth.types.ProteusRealmOptInTargets;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.dns.DnsNotifier;
import be.elevenways.hohenheim.server.dns.DnsServer;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.dns.SecondaryZoneService;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.files.HohenheimSftp;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.hohenheim.server.proxy.ProxyReloadHooks;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.quota.QuotaReconciler;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.hohenheim.server.security.SshAuthWatcher;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.zenit.common.bootstrap.RuntimeJobs;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.cli.HostConsole;
import be.elevenways.zenit.server.net.OptInWatch;
import be.elevenways.zenit.server.task.TaskRuntime;
import be.elevenways.zenit.server.task.TaskService;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Server entry point for Hohenheim: {@code ServerZenitRuntime.run} over {@link HohenheimHost}, then {@link #bootSteps};
 * a stop runs {@link #stopSteps}.
 *
 * AIDEV-NOTE: the two lists are the order, and neither is the other reversed: edit them with
 * ShutdownStepTest's pinned names and ShutdownOrderJourneyTest at hand.
 */
public class ServerMain {

    private static ProxyServer proxyServer;
    private static DnsServer dnsServer;
    private static SecondaryZoneService secondaryZoneService;

    /**
     * The boot's recovery sweeps: a runtime member, so the runtime's stop lets a sweep still writing its settle
     * finish before the datasource closes.
     */
    private static final JobRunnerGroup.Member BOOT_RECOVERY =
        RuntimeJobs.GROUP.member("hohenheim-boot-recovery", JobRunnerGroup.StopMode.DRAIN);

    public static void main(String[] args) {
        // THE process entry point declares the system identity: the command lines, the boot
        // and every virtual thread, pool and timer started from here carry it, which is what
        // lets HohenheimAccess's gates refuse work that declared no identity at all.
        ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.BOOT, () -> start(args));
    }

    private static void start(String[] args) {
        if (ServerZenitRuntime.run(args, HostConsole.SYSTEM, new HohenheimHost())) {
            bootSteps().forEach(HostStep::start);
        }
    }

    /**
     * What Hohenheim starts once HTTP accepts, in order; a step that throws ends the boot thread as it always did.
     *
     * The secret-normalization hooks (the site enable invariant, dyndns token hashing) are no step here: they install
     * via the discovered HohenheimWriteHooks ZenitModule at the MODULES stage, BEFORE the HTTP server binds. (Dyndns
     * tokens live hashed in dns_dyndns_credentials; DynamicDnsService.mintFor is the one writer.)
     */
    static @NonNull List<HostStep> bootSteps() {
        return List.of(
            // Stacks: prove the Docker daemon is reachable, loudly, before anything
            // trusts it (a missing socket used to degrade completely silently); a
            // node without the stacks role never constructs a DockerClient here.
            HostStep.always("docker probe", DockerHealth::probeAtBoot),
            HostStep.on(HohenheimRoles.Role.STACKS, "stack recovery",
                "stack runtime not started, interrupted-deploy sweep skipped", ServerMain::recoverStacks),
            HostStep.on(HohenheimRoles.Role.DATABASES, "database adoption",
                "managed-database adoption skipped, no databases run here", ServerMain::adoptDatabases),
            HostStep.on(HohenheimRoles.Role.INSTANCES, "instance recovery",
                "interrupted-migration settle skipped, no instances run here", ServerMain::recoverInstances),
            // A restart mid-build leaves its build row running, and a running row refuses every later deploy of its
            // instance (deploy_in_progress). Settled beside the release recovery but on no role: builds run wherever
            // the deploy funnel runs, and the instance's operation claim (not this process's roles) proves a row dead.
            // Virtual thread: the claim may wait out a rival controller's live build.
            HostStep.always("build recovery",
                () -> BOOT_RECOVERY.get().fireAndForget(SandboxedBuilds::recoverInterrupted)),
            // A controller killed mid-dump leaves its dump row UPLOADING and possibly a committed artifact; the same
            // process-start fence as the backup settle.
            HostStep.on(HohenheimRoles.Role.DATABASES, "database dump recovery",
                () -> BOOT_RECOVERY.get().fireAndForget(DatabaseBackups::recoverInterrupted)),
            HostStep.on(HohenheimRoles.Role.PROXY, "release recovery",
                "release recovery skipped, no sites are served here", ServerMain::recoverReleases),
            HostStep.always("shutdown registration", ServerMain::registerShutdown),
            // The manager owns its own platform-thread lifecycle lane; never hold
            // HTTP/proxy/DNS startup on its readiness.
            HostStep.on(HohenheimRoles.Role.FIREWALL, "spamservice", "spamservice manager not booted",
                () -> SpamserviceManager.get().boot()),
            HostStep.on(HohenheimRoles.Role.PROXY, "proxy listeners", "proxy listeners not started",
                ServerMain::startProxy),
            HostStep.on(HohenheimRoles.Role.DNS, "dns", "zone store, federation and DNS listeners not started",
                ServerMain::startDns),
            // SFTP to app files, when the operator turned it on: the Files tab's third lane, on
            // every node that serves the Files tab. A failure to bind is kept for the dashboard's
            // attention band, never a refused boot.
            HostStep.always("sftp server", HohenheimSftp::startIfEnabled));
    }

    /**
     * Every shutdown step, in order, each one independent of the others.
     *
     * AIDEV-NOTE: a step that throws is logged and the next one still runs ({@link HostStep#stop}). They used to
     * share one lambda, so a throwing proxy or DNS stop skipped the Spamservice process-group
     * cleanup (a setsid child that outlives the JVM outside systemd), the journalctl follow
     * and the host lease hand-back. The ingress stops first, then what it feeds (the ban
     * writer), then the workers, and the host leases go back last: every step that writes
     * the database runs before the runtime closes it.
     */
    static @NonNull List<HostStep> stopSteps() {
        return List.of(
            HostStep.always("proxy listeners", () -> {
                if (proxyServer != null) {
                    proxyServer.stop();
                }
            }),
            HostStep.always("dns listeners", () -> {
                if (dnsServer != null) {
                    dnsServer.stop();
                }
            }),
            // No viewer subscribes once the ingress stopped; the daemon stats streams and their pumps end here.
            HostStep.always("instance stats streams", InstanceStats::shutdown),
            HostStep.always("secondary zone replication", () -> {
                if (secondaryZoneService != null) {
                    secondaryZoneService.stop();
                }
            }),
            HostStep.always("sftp server", HohenheimSftp::stop),
            HostStep.always("spamservice", () -> SpamserviceManager.get().shutdown()),
            // Destroy the journalctl child; a leaked follow survives the JVM.
            HostStep.always("ssh auth watcher", SshAuthWatcher.INSTANCE::stop),
            // The automatic bans the stopped listeners and watcher queued are written now, not after the close.
            HostStep.always("ban writer", ServerMain::drainBanWriter),
            HostStep.on(HohenheimRoles.Role.STACKS, "stack workers", () -> StackRuntime.get().stopWorkers()),
            // Hand the host leases back so a successor controller does not have to
            // wait out the TTL; a crash still recovers through expiry.
            HostStep.always("host leases", () -> HostLeases.production().releaseAll()));
    }

    private static void recoverStacks() {
        // A restart mid-deploy leaves stacks claiming "deploying", which would
        // disable their monitoring forever. Swept on a virtual thread: the sweep
        // does live Docker inspects (60s timeout each), and a wedged daemon --
        // a common reason for the restart -- must never hold the proxy, DNS and
        // security engine off the wire for it. A monitor tick racing the sweep
        // is safe: both serialize on the per-stack worker.
        BOOT_RECOVERY.get().fireAndForget(() -> StackRuntime.get().resetInterruptedDeploys());
        // The documented migration of the stack lowering onto instances: a stack whose
        // services own no instances is re-deployed under the contract onto its
        // EXISTING volumes, and its pre-lowering containers are retired one by one
        // (only where the daemon still attributes them to that stack). Idempotent.
        BOOT_RECOVERY.get().fireAndForget(StackInstances::adoptExisting);
    }

    private static void adoptDatabases() {
        // The documented migration of the database lowering onto instances: a database
        // record predating it owns no instance, so it gets one and is re-deployed
        // onto its EXISTING data volume rather than being abandoned with a running
        // container nothing tracks. Idempotent, so it is a fast no-op once adopted.
        // Then every redis engine whose stored environment names no password gets one,
        // which its next start demands. First of all, every record a stopped controller
        // left provisioning goes back to the provisioning pool, which adoption leaves alone.
        // Virtual thread: it does live daemon work per record.
        BOOT_RECOVERY.get().fireAndForget(() -> {
            new DatabaseService().recoverInterruptedProvisioning();
            DatabaseInstances.adoptExisting();
            new DatabaseService().repairRedisPasswords();
        });
    }

    private static void recoverInstances() {
        // A controller killed mid-migration leaves a record MIGRATING with its
        // destination pointer set; the settle decides rollback vs completion from
        // daemon attribution so ownership can never stay split. Virtual thread:
        // it does live daemon work on up to two hosts per record.
        //
        // The reservation ledger drifts whenever an instance write is refused AFTER
        // the owner buckets were spent (the host-budget refusal is the reachable
        // shape): a before-write hook cannot compensate a sibling hook's throw, and
        // an instance save carries no transaction to roll one back. Recomputed from
        // the live rows here, so a drifted control plane heals on the next boot
        // instead of locking an owner out one refusal at a time.
        //
        // AIDEV-NOTE: the reconcile runs AFTER the migration settle, on the same
        // thread, never beside it. The settle moves host bookings (a completed handoff
        // books the destination, a rollback closes the window), and the reconcile
        // reads migrate_reserved_mb as truth: run concurrently, its double scan either
        // abstained for the whole boot or corrected a bucket mid-move. Still off the
        // boot thread, like every other daemon-touching settle here.
        BOOT_RECOVERY.get().fireAndForget(() -> {
            try {
                InstanceMigrations.recoverInterrupted();
            } finally {
                QuotaReconciler.reconcile();
            }
        });
        // A controller killed mid-upload leaves a backup row UPLOADING forever
        // (invisible to the dashboard, never swept) and possibly a committed
        // artifact; killed mid-capture it leaves a FAILED snapshot row whose
        // payload nothing reclaims. Both settles fence on the process start time,
        // so rows written by THIS process (live operations) are never touched.
        // Virtual threads: both do target/daemon I/O.
        BOOT_RECOVERY.get().fireAndForget(InstanceBackups::recoverInterrupted);
        BOOT_RECOVERY.get().fireAndForget(() -> new InstanceSnapshots().recoverInterrupted());
        // A controller killed mid-capture or mid-restore leaves the INSTANCE ROW
        // stamped capturing/restoring, and only the in-process outcome paths clear
        // those -- so the record refused deploy, stop, backup and snapshot forever
        // (destroy was the only escape). Same process-start fence as the two settles
        // above; the capture lane settles from daemon truth, an interrupted restore
        // settles to error because the payload may be half-written. Virtual thread:
        // it asks the daemon per record.
        BOOT_RECOVERY.get().fireAndForget(InstanceService::recoverInterrupted);
    }

    private static void recoverReleases() {
        // A restart mid-release leaves a durable operation claiming to be in
        // flight: pre-switch ones lose their candidate (the prior release was
        // never replaced), a half-flipped switch is completed and a lost drain is
        // finished. Virtual thread for the same reason as the stack sweep: it
        // does live daemon work and must never hold the listeners off the wire.
        BOOT_RECOVERY.get().fireAndForget(() -> {
            ArtifactDeploys.recoverInterrupted();
            ReleaseEngine.recoverInterrupted();
        });
        // Preview lifetimes are one-shot record schedules: a deadline missed
        // while down fires at the framework sweeper's next pass, no boot sweep.
    }

    private static void startProxy() {
        proxyServer = new ProxyServer();
        proxyServer.start();
        ProxyReloadHooks.install();
        // Site auth gates live on the proxy: name each Proteus realm a live upgrade left failing closed.
        OptInWatch.install(HohenheimSettings.ProxyAuth.PROTEUS_ALLOW_PRIVATE_NETWORKS,
            SiteAuthProviderModel.SCHEMA, ProteusRealmOptInTargets::targetsOf);
    }

    private static void startDns() {
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
    }

    /**
     * Hands the shutdown steps to the runtime's own stop, before the first managed child can start.
     *
     * AIDEV-NOTE: an OWNED RESOURCE of the runtime, never a JVM shutdown hook of our own. The runtime closes its owned
     * resources in reverse registration order after its pools ended, and the datasource registered first, so these
     * steps run (and their workers drain) while it is still open. A hook of our own ran BESIDE the runtime's hook and
     * lost that race on every stop: the DNS secondary refresh and a proxy reload logged "Datasource is required but
     * was null".
     */
    private static void registerShutdown() {
        ServerZenitRuntime.registerOwnedResource(() -> ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.SHUTDOWN,
            () -> stopSteps().forEach(HostStep::stop)));
    }

    private static void drainBanWriter() {
        try {
            BanService.INSTANCE.awaitPendingWrites();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the ban writer drained", interrupted);
        }
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
