package be.elevenways.hohenheim.server.process;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.ProcessConfinement;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.proxy.ResolvedClientIp;
import be.elevenways.hohenheim.server.security.ProcessNetworkPolicy;
import be.elevenways.hohenheim.server.security.SecurityReportEnv;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.TcpUpstreamConnection;
import be.elevenways.hohenheim.server.sitetype.UnixSocketBridgeConnection;
import be.elevenways.hohenheim.server.sitetype.UpstreamConnection;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.protoblast.common.Blast;
import io.undertow.server.HttpServerExchange;
import org.checkerframework.checker.nullness.qual.Nullable;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base handler for site types that manage child processes.
 * Handles spawning, monitoring, auto-scaling, crash recovery,
 * and fingerprint-based sticky routing.
 *
 * Subclasses implement buildCommand() to define what to spawn.
 * This makes the base reusable for Node.js, Java, or any other runtime.
 */
public abstract class ManagedProcessSiteHandler implements SiteRequestHandler, ProcessMonitor.StatsListener {

    private static final HttpString X_HOHENHEIM_KEY =
        new HttpString("X-Hohenheim-Key");
    private static final HttpString X_HOHENHEIM_ACTION =
        new HttpString("X-Hohenheim-Action");

    // Configuration
    protected final int siteId;
    protected final String siteName;
    protected final int minProcesses;
    protected final int maxProcesses;
    protected final boolean waitForReady;
    protected final boolean usePorts;
    protected final Map<String, String> environmentVariables;
    /** SHA-256 digests of the site's control-API keys; the plaintext is never held. */
    protected final Set<String> apiKeyDigests;
    /** The operator-declared per-child cgroup caps; both members null means unbounded. */
    protected final ResourceLimits limits;

    // Process state
    private final CopyOnWriteArrayList<ManagedProcess> processList = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, ManagedProcess> processMap = new ConcurrentHashMap<>();
    private final AtomicInteger readyCount = new AtomicInteger(0);
    private final AtomicInteger runningCount = new AtomicInteger(0);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();

    // Processes requested but not yet ready: counted from spawn entry until the child
    // signals ready (or dies first). startMinimumServers subtracts it from its deficit so
    // neither overlapping calls nor repeat calls while wait_for_ready children boot can
    // over-start past the minimum.
    private final AtomicInteger requestedCount = new AtomicInteger(0);

    // Port allocation
    private final PortAllocator portAllocator;

    // Monitoring
    private final ProcessMonitor monitor;

    // IPC and shared cache
    private final RemoteCache remoteCache = new RemoteCache();
    private final ConcurrentHashMap<Long, IpcChannel> ipcChannels = new ConcurrentHashMap<>();

    // Crash recovery
    private final LinkedList<Long> exitLog = new LinkedList<>();
    private static final int MAX_EXIT_LOG = 20;
    private static final int CRASH_THRESHOLD_COUNT = 5;
    private static final long CRASH_BACKOFF_MS = 3000;
    private static final int MAX_EADDRINUSE_RETRIES = 10;
    private static final long STARTUP_GRACE_MS = 750;
    /** Bounded window an exited child gets to drain stdout/stderr before startup diagnostics read the log. */
    private static final long STARTUP_DRAIN_MS = 500;

    private static final ScheduledExecutorService crashRecoveryScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crash-recovery");
            t.setDaemon(true);
            return t;
        });

    // Debounce
    private final AtomicLong lastStartMinimumServers = new AtomicLong(0);

    // One warning per handler when children silently inherit a root daemon user
    private final AtomicBoolean warnedRootInherit = new AtomicBoolean(false);

    // One warning per handler when a site has no uid to key its network isolation on
    private final AtomicBoolean warnedNotIsolatable = new AtomicBoolean(false);

    // Rate limit for the crash-loop notification (one alert per episode, not per exit)
    private static final long CRASH_NOTIFY_INTERVAL_MS = 10 * 60 * 1000;
    private final AtomicLong lastCrashLoopNotified = new AtomicLong(0);

    // Startup queue: requests waiting for a process to become ready. Re-armable: a fresh
    // latch is installed when the last ready process exits, so requests arriving during a
    // later full outage queue again instead of hitting a permanently-open latch.
    // Readers snapshot the field once and await that instance.
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);

    // Periodic proclog flush so a hard crash (kill -9, OOM) loses at most one interval of
    // output. AIDEV-NOTE: This per-process maintenance is a process-lifecycle concern and
    // deliberately stays on the handler's own executor — it is NOT a site-wide scheduled
    // job, so it does not belong in the zenit TaskService that replaced TaskScheduler.
    private static final long PROCLOG_FLUSH_SECONDS = 30;
    private static final ScheduledExecutorService proclogFlushScheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "proclog-flush");
            t.setDaemon(true);
            return t;
        });
    private volatile ScheduledFuture<?> proclogFlushTask;

    // Scaling constants
    private static final int SCALE_UP_CPU_THRESHOLD = 50;
    private static final long SCALE_UP_SUSTAIN_MS = 15_000;
    private static final int SCALE_UP_HARD_CAP = 5;
    private static final long SCALE_DOWN_IDLE_MS = 180_000;
    private static final int SCALE_DOWN_CPU_THRESHOLD = 0;
    private static final int ROUTING_CPU_CEILING = 92;
    private static final long READY_TIMEOUT_MS = 60_000;

    /**
     * Whether a managed process is held out of the routing pool until it sends a ready signal
     * when the site settings don't specify {@code wait_for_ready}. Overridden by Alchemy (which
     * always signals readiness via janeway) to return true. Must return a constant -- it is
     * called during construction.
     */
    protected boolean defaultWaitForReady() {
        return false;
    }

    /** Whether managed processes are held out of the routing pool until they signal ready. */
    public boolean isWaitForReady() {
        return waitForReady;
    }

    /** The digests this handler will admit on; never the plaintext keys. */
    public Set<String> getApiKeyDigests() {
        return Collections.unmodifiableSet(apiKeyDigests);
    }

    protected ManagedProcessSiteHandler(int siteId, String siteName, Map<String, Object> settings,
                                         PortAllocator portAllocator, ProcessMonitor monitor) {
        if (monitor == null || portAllocator == null) {
            // roles.processes is off (ProcessInfrastructure.init never ran): every
            // managed-process site type's createHandler catches this and serves an
            // explicit FaultedSiteHandler 503 instead of NPEing mid-request.
            throw new IllegalArgumentException(
                "managed process infrastructure is not running (roles.processes is disabled)");
        }
        this.siteId = siteId;
        this.siteName = siteName;
        this.portAllocator = portAllocator;
        this.monitor = monitor;

        Object minObj = settings.get("minimum_processes");
        this.minProcesses = minObj instanceof Integer i && i > 0 ? i : 1;
        Object maxObj = settings.get("maximum_processes");
        this.maxProcesses = maxObj instanceof Integer i && i > 0 ? i : SCALE_UP_HARD_CAP;
        this.waitForReady = settings.containsKey("wait_for_ready")
            ? Boolean.TRUE.equals(settings.get("wait_for_ready"))
            : defaultWaitForReady();
        // use_ports=true routes through a loopback TCP port instead of a unix socket.
        // Sockets are the DEFAULT (the original's posture); handlers without socket
        // support (no allocateSocketPath) fall back to port mode automatically.
        this.usePorts = Boolean.TRUE.equals(settings.get("use_ports"));

        // API keys are stored as digests. AIDEV-NOTE: normalize() also hashes a
        // legacy PLAINTEXT entry, so a key configured before the hashing change
        // keeps authenticating even on a datasource the seeder has not swept yet.
        this.apiKeyDigests = new LinkedHashSet<>(
            SiteApiKeys.normalize(settings.get(SiteApiKeys.SETTING_NAME)));

        // Parse environment variables from settings
        this.environmentVariables = EnvVars.toMap(settings.get("environment_variables"));

        // A DECLARED memory limit is a promise this host must be able to keep. Refusing
        // here (not at spawn) makes it a FaultedSiteHandler 503 naming the reason, the
        // same shape as an unresolvable run-as user -- never a site that half-starts.
        this.limits = ResourceLimits.fromSettings(settings);
        if (this.limits.bookedMemoryMb() > 0 && !ProcessConfinement.availability().enforceable()) {
            throw new IllegalArgumentException(ProcessConfinement.unenforceableRefusal(siteName));
        }

        monitor.addListener(siteId, this);

        this.proclogFlushTask = proclogFlushScheduler.scheduleAtFixedRate(
            this::flushProclogs, PROCLOG_FLUSH_SECONDS, PROCLOG_FLUSH_SECONDS, TimeUnit.SECONDS);
    }

    /** Periodic flush: persist every live process's rolling log so a hard crash loses little. */
    private void flushProclogs() {
        for (ManagedProcess managed : processMap.values()) {
            try {
                persistProclog(managed);
            } catch (Exception e) {
                Blast.log("PROCESS: proclog flush failed for", siteName,
                    "pid=" + managed.pid(), "-", e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Abstract: subclasses define what command to spawn
    // -----------------------------------------------------------------------

    /**
     * Build the command to execute (e.g., ["node", "app.js", "--port=3000"]).
     * The port is already allocated and passed as a parameter.
     */
    protected abstract List<String> buildCommand(String listenTarget);

    /**
     * Allocate a unix socket path for a child when {@code use_ports=false}, or null if this handler
     * does not support socket transport (port mode is then used). Resolved before spawn so it can be
     * passed to the child via env.
     */
    protected String allocateSocketPath() {
        return null;
    }

    /** Release a socket path previously returned by {@link #allocateSocketPath()}. */
    protected void releaseSocketPath(String socketPath) {
    }

    /**
     * Build additional environment variables specific to the runtime.
     * The base environment (PORT, system env) is already set.
     */
    protected abstract Map<String, String> buildRuntimeEnvironment(int port);

    /**
     * Connection variables for the site's attached managed databases, resolved per spawn.
     * Overridable so handler-level tests can run without the injection tables.
     */
    protected Map<String, String> resolveInjectedEnvironment() {
        Map<String, String> env = new LinkedHashMap<>(DatabaseEnvInjection.envForSite(siteId));
        env.putAll(SecurityReportEnv.forSite(siteId));
        return env;
    }

    /**
     * The working directory for the child process.
     */
    protected abstract File getWorkingDirectory();

    /**
     * Optional: the identity to run the process as, or null to inherit the daemon's own user.
     */
    protected SystemUsers.@Nullable RunAsUser getRunAsUser() { return null; }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Start the minimum number of processes. The deficit subtracts {@link #requestedCount}
     * (spawns requested but not yet ready), so neither overlapping calls nor repeat calls
     * while wait_for_ready children boot can over-start past the minimum. The debounce CAS
     * serializes truly concurrent callers.
     */
    public void startMinimumServers() {
        if (destroyed.get()) return;
        long now = System.currentTimeMillis();
        long prev = lastStartMinimumServers.get();
        if (now - prev < 500 || !lastStartMinimumServers.compareAndSet(prev, now)) return;

        int deficit = minProcesses - activeProcessCount() - requestedCount.get();
        for (int i = 0; i < deficit; i++) {
            startProcess();
        }
    }

    /**
     * Spawn a single new child process. The spawn is counted in {@link #requestedCount}
     * from entry until the child becomes ready or dies, so capacity that is still booting
     * is visible to startMinimumServers.
     */
    public ManagedProcess startProcess() {
        if (destroyed.get()) return null;
        if (!isolate()) return null;
        long backoffMs = 150;
        requestedCount.incrementAndGet();

        boolean spawned = false;
        try {
            for (int attempt = 1; attempt <= MAX_EADDRINUSE_RETRIES; attempt++) {
                if (destroyed.get()) return null;
                ManagedProcess managed;
                try {
                    managed = startProcessOnce();
                } catch (CapacityRefused refused) {
                    // NOT the EADDRINUSE shape: a host that has no room now will have no
                    // room 150ms later either, so retrying it ten times only delays the
                    // refusal by half a minute and hides it behind a backoff log.
                    Blast.log("PROCESS: refusing to start another child of", siteName, "-",
                        refused.getMessage());
                    return null;
                }

                if (managed != null) {
                    spawned = true;
                    return managed;
                }

                if (attempt == MAX_EADDRINUSE_RETRIES) {
                    return null;
                }

                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }

                backoffMs = Math.min(backoffMs * 2, 5_000);
            }

            return null;
        } finally {
            if (!spawned) {
                requestedCount.updateAndGet(v -> Math.max(0, v - 1));
            }
            // On success the slot stays reserved until markProcessReady() or a
            // pre-ready processExit() releases it.
        }
    }

    /**
     * A spawn the host budget refused: permanent for as long as the host is full, so it
     * must never ride the address-in-use retry loop.
     */
    private static final class CapacityRefused extends RuntimeException {
        CapacityRefused(String message) {
            super(message);
        }
    }

    /** Alert the notification channels about a crash loop, at most once per interval. */
    private void notifyCrashLoop(long now) {
        long previous = lastCrashLoopNotified.get();
        if (now - previous < CRASH_NOTIFY_INTERVAL_MS
            || !lastCrashLoopNotified.compareAndSet(previous, now)) {
            return;
        }
        try {
            Alerts.send(NotificationEvents.PROCESS_CRASH_LOOP,
                "Crash loop: " + siteName,
                "Processes of site '" + siteName + "' keep exiting shortly after start;"
                    + " restarts are being throttled. Check the process logs.");
        } catch (Exception e) {
            Blast.log("PROCESS: could not send crash-loop notification -", e.getMessage());
        }
    }

    /**
     * Apply this site's kernel isolation before anything is spawned, or refuse the spawn.
     *
     * AIDEV-NOTE: applied per SPAWN, not once per handler, and that is what closes this
     * tier's REBOOT window without a sweep entry doing it: a reboot takes every child with
     * it, so they all come back through here and re-apply a ruleset the kernel lost. It
     * does NOT close the plain CONTROLLER-RESTART window, and an earlier version of this
     * note claiming "or any restart" was simply false: a ProcessBuilder child is reparented
     * to init and keeps running (and keeps its listening socket -- see
     * {@link PortAllocator}) across a controller restart, so its uid chain is whatever the
     * previous generation left and no spawn re-applies it. {@link ProcessReaper} is what
     * makes the claim true now: it kills those survivors at boot, before anything spawns,
     * so every running child really is one this method admitted. The container tiers cannot
     * rely on any of it -- the Docker daemon restarts unless-stopped containers with no
     * code path of ours in the loop -- which is why {@code VerifyWorkloadIsolation} exists
     * for THEM and covers this tier only for the mid-life divergence (something else
     * flushing the table under a long-running child).
     *
     * AIDEV-NOTE: a site with no run-as user gets no policy, because the only identity its
     * children have is the DAEMON's own uid and a deny keyed on that would cut the control
     * plane off from its own hosts. That shape is not a hole opened here: it exists only
     * while {@code process.require_dedicated_user} is off AND no tenant manages the site
     * ({@code WorkloadIdentity.forSite} refuses it otherwise), which is this tier's spelling
     * of the container tiers' declared {@code NetworkPosture.SHARED_BRIDGE}. It is warned
     * about, never silent.
     *
     * AIDEV-NOTE: {@link #destroy()} deliberately does NOT remove the uid chain, unlike the
     * network tiers' destroy paths. A handler is REPLACED (new one constructed, old one
     * destroyed) whenever a site's settings change, so a removal here would race the
     * replacement into deleting the policy out from under freshly spawned children. A
     * leftover chain denies a uid nothing is running as, which is inert; the chain is
     * rewritten on the next spawn either way.
     *
     * @return false when the spawn is refused
     */
    private boolean isolate() {
        SystemUsers.RunAsUser runAs = getRunAsUser();
        if (runAs == null) {
            warnNotIsolatable();
            return true;
        }
        try {
            ProcessNetworkPolicy.current().apply(runAs.uid(), siteName);
            return true;
        } catch (IOException refused) {
            Blast.log("PROCESS:", refused.getMessage());
            return false;
        }
    }

    /** Warn once per handler when a site's children cannot be isolated for lack of a uid. */
    private void warnNotIsolatable() {
        if (warnedNotIsolatable.compareAndSet(false, true)) {
            Blast.log("PROCESS: site", siteName, "has no system user configured, so its child",
                "processes run as the Hohenheim daemon and CANNOT be network-isolated;",
                "configure a system user for this site.");
        }
    }

    /**
     * The uid this site's children actually run as, or null when they inherit the daemon's.
     * The sweep asks the LIVE handler rather than re-reading settings: what the kernel must
     * carry is the identity the running children have, not the one the record now names.
     */
    public @Nullable Integer runAsUid() {
        SystemUsers.RunAsUser runAs = getRunAsUser();
        return runAs != null ? runAs.uid() : null;
    }

    /** Kill every child of this site; the respawn re-applies (or re-refuses) the policy. */
    public void killAllProcesses() {
        for (ManagedProcess managed : processMap.values()) {
            managed.kill();
        }
    }

    /** Warn once per handler when children of a root daemon spawn without a privilege drop. */
    private void warnIfInheritingRoot() {
        if (SystemUsers.daemonRunsAsRoot() && warnedRootInherit.compareAndSet(false, true)) {
            Blast.log("PROCESS: site", siteName, "has no system user configured;",
                "its child processes inherit the root daemon user.",
                "Configure a system user for this site to drop privileges.");
        }
    }

    private ManagedProcess startProcessOnce() {
        // use_ports=false routes through a unix socket; otherwise a loopback TCP port. The listen
        // target (a port number or socket path) is passed to the child as PORT, --port=<x>, and,
        // for sockets, PATH_TO_SOCKET (matching the Node Hohenheim child contract).
        String socketPath = usePorts ? null : allocateSocketPath();
        boolean socketMode = socketPath != null;

        int port = 0;
        if (!socketMode) {
            try {
                port = portAllocator.allocate(siteId);
            } catch (Exception e) {
                Blast.log("PROCESS: failed to allocate port for", siteName, "-", e.getMessage());
                return null;
            }
        }
        String listenTarget = socketMode ? socketPath : String.valueOf(port);

        // Book this child's declared memory on the host budget BEFORE it exists. The unit
        // booked is the child, not the site, and the cap stamped on the cgroup below is the
        // same number -- so the auto-scaler stops at the host's real headroom instead of at
        // its own hard cap of five. A site that declares nothing books nothing.
        int bookedMb = this.limits.bookedMemoryMb();
        if (bookedMb > 0) {
            try {
                ProcessCapacity.reserve(ServerModel.localServerId(), bookedMb);
            } catch (RuntimeException full) {
                releaseUpstream(port, socketPath, null, 0);
                throw new CapacityRefused(full.getMessage());
            }
        }

        List<String> command = buildCommand(listenTarget);
        File workDir = getWorkingDirectory();

        IpcChannel ipc = null;
        UpstreamConnection connection = null;
        try {
            // Create IPC channel before spawning so the port is ready
            ipc = new IpcChannel();
            ipc.startAccepting();

            SystemUsers.RunAsUser runAs = getRunAsUser();
            Map<String, String> env = new LinkedHashMap<>(SystemUsers.safeEnvironment(
                runAs != null ? runAs.home() : System.getProperty("user.home")));
            // Attached-database credentials resolve fresh per spawn (live published port);
            // operator-authored variables may override the NON-reserved injected values
            // (e.g. pointing a site at an external DATABASE_*), which is warned, not refused.
            Map<String, String> injected = resolveInjectedEnvironment();
            env.putAll(injected);
            env.putAll(environmentVariables);
            env.putAll(buildRuntimeEnvironment(port));
            ReservedEnv.warnOverriddenInjected(injected, environmentVariables, siteName);

            // The IPC port is reachable by every local user, so the token travels with
            // it: only this child's process environment holds it, and the channel
            // refuses everyone else (WorkloadIdentity keeps that environment private
            // by refusing shared run-as users). Stamped LAST -- see ReservedEnv.stamp.
            Map<String, String> reserved = new LinkedHashMap<>();
            reserved.put("PORT", listenTarget);
            reserved.put("PATH_TO_SOCKET", socketMode ? socketPath : null);
            reserved.put("HOHENHEIM_IPC_PORT", String.valueOf(ipc.getPort()));
            reserved.put("HOHENHEIM_IPC_TOKEN", ipc.getSecret());
            reserved.put(SecurityReportEnv.URL_VAR, injected.get(SecurityReportEnv.URL_VAR));
            reserved.put(SecurityReportEnv.TOKEN_VAR, injected.get(SecurityReportEnv.TOKEN_VAR));
            String home = runAs != null ? runAs.home() : System.getProperty("user.home");
            reserved.put("HOME", home != null && !home.isBlank() ? home : null);
            ReservedEnv.stamp(env, reserved, siteName);

            // The cgroup scope that makes the booking above honest; empty when nothing is
            // declared, and a hard refusal when a declaration cannot be enforced here.
            List<String> confinement = ProcessConfinement.scopePrefix(
                siteName, this.limits.memoryMb(), this.limits.cpus());
            if (!confinement.isEmpty()) {
                ProcessConfinement.contributeEnvironment(env);
            }

            ProcessBuilder pb = SystemUsers.executionBuilder(runAs, env, command, true,
                confinement);
            if (workDir != null && workDir.exists()) {
                pb.directory(workDir);
            }
            pb.redirectErrorStream(false);
            if (runAs == null) {
                warnIfInheritingRoot();
            }

            Process process = pb.start();
            ManagedProcess managed = new ManagedProcess(process, port, socketPath, siteId, runAs);
            managed.setBookedMemoryMb(bookedMb);
            List<Thread> outputThreads = captureProcessOutput(managed);

            if (process.waitFor(STARTUP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                awaitProcessOutput(outputThreads);
                persistProclog(managed);
                ipc.close();
                releaseUpstream(port, socketPath, null, managed.takeBooking());

                if (managed.hasAddressInUse()) {
                    Blast.log("PROCESS: retrying", siteName, "after EADDRINUSE on", listenTarget);
                } else {
                    Blast.log("PROCESS: failed to start", siteName,
                        "exit=" + process.exitValue(), "target=" + listenTarget);
                }

                return null;
            }

            // Survived the grace window: open the upstream transport. For socket mode this stands up
            // the loopback bridge, whose connect retry covers the child still binding its socket.
            if (socketMode) {
                connection = new UnixSocketBridgeConnection(socketPath, false);
            } else {
                connection = new TcpUpstreamConnection(
                    new URI("http", null, "127.0.0.1", port, "/", null, null), false);
            }
            managed.setConnection(connection);

            synchronized (lifecycleLock) {
                if (destroyed.get()) {
                    managed.kill();
                    ipc.close();
                    releaseUpstream(port, socketPath, connection, managed.takeBooking());
                    return null;
                }

                // Wire up IPC message handler
                IpcChannel finalIpc = ipc;
                ipc.setMessageHandler(msg -> handleIpcMessage(managed, finalIpc, msg));
                ipcChannels.put(process.pid(), ipc);

                processMap.put(process.pid(), managed);
                runningCount.incrementAndGet();

                if (!waitForReady) {
                    markProcessReady(managed);
                }

                monitor.register(managed);
                process.onExit().thenAccept(p -> processExit(managed));

                if (waitForReady) {
                    scheduleReadyTimeout(managed);
                }
            }

            Blast.log("PROCESS: started", siteName, "pid=" + process.pid(), "target=" + listenTarget);
            return managed;

        } catch (Exception e) {
            if (ipc != null) ipc.close();
            releaseUpstream(port, socketPath, connection, bookedMb);
            Blast.log("PROCESS: failed to start", siteName, "-", e.getMessage());
            return null;
        }
    }

    /** Release a process's upstream resources: TCP port, socket file, and bridge connection. */
    private void releaseUpstream(int port, String socketPath, UpstreamConnection connection) {
        releaseUpstream(port, socketPath, connection, 0);
    }

    /**
     * The same release, plus the host-memory booking the child was admitted under.
     *
     * AIDEV-NOTE: the booking is released on EVERY path a port is, deliberately -- the two
     * have the identical lifetime (one process instance) and losing one leaks host budget
     * exactly the way losing the other leaks a port. The amount comes from the child's own
     * stamp, never re-derived from settings that may have changed since it started.
     */
    private void releaseUpstream(int port, String socketPath, UpstreamConnection connection,
                                 int bookedMb) {
        if (port > 0) {
            portAllocator.release(port);
        }
        if (socketPath != null) {
            releaseSocketPath(socketPath);
        }
        if (connection != null) {
            connection.close();
        }
        if (bookedMb > 0) {
            ProcessCapacity.release(ServerModel.localServerId(), bookedMb);
        }
    }

    // AIDEV-NOTE: these pumps MUST be platform threads. Virtual-thread blocking
    // reads on Process pipes pinned both carrier threads on a 1-vCPU host and
    // starved every virtual-thread task including the DNS responders
    // (2026-07-22 VPS incident). Guarded by
    // ManagedProcessSiteHandlerTest.processOutputUsesPlatformThreadsForBlockingPipes.
    private List<Thread> captureProcessOutput(ManagedProcess managed) {
        return List.of(
            Thread.ofPlatform().daemon().name("process-output-" + managed.pid() + "-stdout")
                .start(() -> pumpStream(managed.process().getInputStream(), managed, false)),
            Thread.ofPlatform().daemon().name("process-output-" + managed.pid() + "-stderr")
                .start(() -> pumpStream(managed.process().getErrorStream(), managed, true))
        );
    }

    /** Gives exited children a bounded window to drain stdout and stderr before diagnostics are read. */
    private void awaitProcessOutput(List<Thread> outputThreads) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_DRAIN_MS);
        for (Thread thread : outputThreads) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Read raw chunks from the child's stdout/stderr and feed them to the
     * managed log verbatim (ANSI preserved for the terminal viewer). Stderr
     * is additionally scanned for EADDRINUSE so the startup retry loop can
     * detect address-in-use failures.
     */
    private void pumpStream(InputStream in, ManagedProcess managed, boolean scanForAddrInUse) {
        byte[] buf = new byte[4096];
        try {
            int len;
            while ((len = in.read(buf)) != -1) {
                String chunk = new String(buf, 0, len, StandardCharsets.UTF_8);
                if (scanForAddrInUse && chunk.contains("EADDRINUSE")) {
                    managed.markAddressInUse();
                }
                managed.appendLog(chunk);
            }
        } catch (Exception ignored) {
            // Process ended or stream closed
        }
    }

    /**
     * A wait_for_ready child that never signals is killed after the timeout instead of
     * lingering as "requested" forever and silently exhausting the process budget
     * (the original killed at 60s too). The exit path handles cleanup and restart.
     */
    private void scheduleReadyTimeout(ManagedProcess managed) {
        proclogFlushScheduler.schedule(() -> {
            if (!managed.isReady() && processMap.containsKey(managed.pid())) {
                Blast.log("PROCESS: no ready signal from", siteName,
                    "pid=" + managed.pid(), "within", readyTimeoutMs() + "ms; killing");
                managed.kill();
            }
        }, readyTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    /** How long a wait_for_ready child may boot before it is killed (test-overridable). */
    protected long readyTimeoutMs() {
        return READY_TIMEOUT_MS;
    }

    private void markProcessReady(ManagedProcess managed) {
        if (managed.isReady()) return;
        managed.setReady(true);
        processList.add(managed);
        readyCount.incrementAndGet();
        requestedCount.updateAndGet(v -> Math.max(0, v - 1));
        readyLatch.countDown();
    }

    // -----------------------------------------------------------------------
    // IPC message handling
    // -----------------------------------------------------------------------

    private void handleIpcMessage(ManagedProcess proc, IpcChannel ipc, Map<String, Object> msg) {
        // Legacy envelope: the original Node IPC wrapped everything as
        // {"hohenheim":{...}} / {"alchemy":{...}} -- unwrap so old children keep working.
        Object legacy = msg.get("hohenheim") != null ? msg.get("hohenheim") : msg.get("alchemy");
        if (legacy instanceof Map<?, ?> data) {
            if (Boolean.TRUE.equals(data.get("ready"))) {
                msg = Map.of("type", "ready");
            } else if (data.get("error") instanceof Map<?, ?> error
                && "EADDRINUSE".equals(error.get("code"))) {
                msg = Map.of("type", "error", "code", "EADDRINUSE");
            } else {
                return;
            }
        }
        String type = msg.get("type") instanceof String s ? s : "";
        switch (type) {
            case "ready" -> {
                if (waitForReady) {
                    markProcessReady(proc);
                    Blast.log("PROCESS: ready signal from", siteName, "pid=" + proc.pid());
                }
            }
            case "error" -> {
                // A child reporting EADDRINUSE over IPC gets the same retry treatment
                // as the stderr scan; anything else just logs.
                if ("EADDRINUSE".equals(msg.get("code"))) {
                    proc.markAddressInUse();
                    proc.kill();
                } else {
                    // e.g. the wrapper's IPC_OUTBOX_OVERFLOW report carries a dropped count.
                    Blast.log("PROCESS: child error via IPC for", siteName,
                        "pid=" + proc.pid(), "-", msg);
                }
            }
            case "remcache_set" -> {
                String key = (String) msg.get("key");
                Object value = msg.get("value");
                Object maxAgeObj = msg.get("maxAge");
                long maxAge = maxAgeObj instanceof Number n ? n.longValue() : 0;
                if (key != null) remoteCache.set(key, value, maxAge);
            }
            case "remcache_get" -> {
                String key = (String) msg.get("key");
                Object id = msg.get("id");
                Object value = key != null ? remoteCache.get(key) : null;
                if (id != null) ipc.sendResponse(id, value);
            }
            case "remcache_peek" -> {
                String key = (String) msg.get("key");
                Object id = msg.get("id");
                Object value = key != null ? remoteCache.peek(key) : null;
                if (id != null) ipc.sendResponse(id, value);
            }
            case "remcache_remove" -> {
                String key = (String) msg.get("key");
                if (key != null) remoteCache.remove(key);
            }
        }
    }

    /**
     * Broadcast a message to all child processes via their IPC channels.
     */
    public void broadcast(Map<String, Object> body) {
        // Flatten the body into the message itself since the IPC JSON serializer
        // does not support nested maps.
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "hohenheim_broadcast");
        if (body != null) {
            msg.putAll(body);
        }
        for (IpcChannel ipc : ipcChannels.values()) {
            ipc.send(msg);
        }
    }

    // -----------------------------------------------------------------------
    // Process exit and crash recovery
    // -----------------------------------------------------------------------

    private void processExit(ManagedProcess managed) {
        long pid = managed.pid();
        int port = managed.port();

        Blast.log("PROCESS: exited", siteName, "pid=" + pid);

        // Persist the process log before cleanup
        persistProclog(managed);

        // Cleanup IPC channel
        IpcChannel ipc = ipcChannels.remove(pid);
        if (ipc != null) ipc.close();

        // Cleanup
        monitor.unregister(pid);
        releaseUpstream(port, managed.socketPath(), managed.connection(),
            managed.takeBooking());
        processMap.remove(pid);
        processList.remove(managed);

        runningCount.updateAndGet(v -> Math.max(0, v - 1));

        if (managed.isReady()) {
            int remaining = readyCount.updateAndGet(v -> Math.max(0, v - 1));
            if (remaining == 0) {
                // Full outage: re-arm so requests queue for the next ready process
                // instead of racing a permanently-open latch.
                readyLatch = new CountDownLatch(1);
            }
        } else {
            // Died while booting: release its requested-capacity slot.
            requestedCount.updateAndGet(v -> Math.max(0, v - 1));
        }

        if (destroyed.get()) return;

        // Track exit for crash detection
        long now = System.currentTimeMillis();
        synchronized (exitLog) {
            exitLog.add(now);
            while (exitLog.size() > MAX_EXIT_LOG) exitLog.removeFirst();

            // Crash backoff: if >5 exits within a short window, delay restart
            if (exitLog.size() > CRASH_THRESHOLD_COUNT) {
                long oldest = exitLog.getFirst();
                long windowMs = now - oldest;
                long threshold = 2500L * exitLog.size();

                if (windowMs < threshold) {
                    Blast.log("PROCESS: crash loop detected for", siteName,
                              "- scheduling delayed restart in", CRASH_BACKOFF_MS, "ms");
                    notifyCrashLoop(now);
                    crashRecoveryScheduler.schedule(this::startMinimumServers,
                        CRASH_BACKOFF_MS, TimeUnit.MILLISECONDS);
                    return;
                }
            }
        }

        // Restart to meet minimum
        startMinimumServers();
    }

    // -----------------------------------------------------------------------
    // Stats listener: auto-scaling
    // -----------------------------------------------------------------------

    @Override
    public void onStats(ManagedProcess proc, int cpuPercent, long memoryKb) {
        long now = System.currentTimeMillis();

        // --- Auto-scale up ---
        if (cpuPercent > SCALE_UP_CPU_THRESHOLD) {
            long overloadStart = proc.startOverload().get();
            if (overloadStart == 0) {
                proc.startOverload().set(now);
            } else if (now - overloadStart > SCALE_UP_SUSTAIN_MS) {
                int active = activeProcessCount();
                if (runningCount.get() < SCALE_UP_HARD_CAP && active < maxProcesses) {
                    Blast.log("PROCESS: scaling up", siteName, "(CPU " + cpuPercent + "%)");
                    proc.startOverload().set(0);
                    startProcess();
                }
            }
            proc.startIdle().set(0);
        }
        // --- Auto-scale down ---
        else if (cpuPercent == SCALE_DOWN_CPU_THRESHOLD) {
            long idleStart = proc.startIdle().get();
            if (idleStart == 0) {
                proc.startIdle().set(now);
            } else if (now - idleStart > SCALE_DOWN_IDLE_MS) {
                if (activeProcessCount() > minProcesses) {
                    Blast.log("PROCESS: scaling down", siteName, "pid=" + proc.pid(), "(idle)");
                    proc.kill();
                }
            }
        } else {
            // CPU > 0 but <= threshold: reset both timers
            proc.startOverload().set(0);
            proc.startIdle().set(0);
        }

        // --- Kill isolated processes with no fingerprints ---
        if (proc.isIsolated() && proc.activeFingerprintCount() == 0) {
            Blast.log("PROCESS: killing isolated process", siteName, "pid=" + proc.pid());
            proc.kill();
        }
    }

    // -----------------------------------------------------------------------
    // Request handling: fingerprint routing
    // -----------------------------------------------------------------------

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
        // Control-API requests: a request carrying BOTH headers is claiming the
        // control API, so it is answered here and never proxied on -- a wrong key
        // is an authentication failure, not ordinary traffic to hand upstream.
        // AIDEV-NOTE: ForwardingHeaders.applyRequestHeaders strips X-Hohenheim-Key
        // before dispatching, so this branch is currently unreachable through the
        // public proxy listener. The admission policy is hardened anyway: whoever
        // makes it reachable again must not also have to rediscover that the check
        // needs hashing, constant-time compare and a throttle.
        String key = exchange.getRequestHeaders().getFirst(X_HOHENHEIM_KEY);
        String actions = exchange.getRequestHeaders().getFirst(X_HOHENHEIM_ACTION);
        switch (SiteApiKeys.decide(siteId, apiKeyDigests, key, actions,
                ResolvedClientIp.get(exchange))) {
            case THROTTLED -> {
                exchange.setStatusCode(429);
                exchange.getResponseHeaders().put(Headers.RETRY_AFTER, "60");
                exchange.getResponseSender().send("Too many API key attempts");
                return;
            }
            case REFUSED -> {
                exchange.setStatusCode(403);
                exchange.getResponseSender().send("Invalid API key");
                return;
            }
            case ALLOWED -> {
                handleApiRequest(exchange, actions);
                return;
            }
            case NOT_CONTROL -> { }
        }

        // Wait for at least one process to be ready. Snapshot the latch before awaiting:
        // processExit may swap in a fresh latch concurrently, and awaiting the snapshot
        // pairs with the markProcessReady countdown for the same outage window.
        if (readyCount.get() == 0) {
            CountDownLatch latch = this.readyLatch;
            startMinimumServers();
            try {
                if (readyCount.get() == 0 && !latch.await(60, TimeUnit.SECONDS)) {
                    exchange.setStatusCode(503);
                    exchange.getResponseSender().send("Service starting up - please retry");
                    return;
                }
            } catch (InterruptedException e) {
                exchange.setStatusCode(503);
                exchange.getResponseSender().send("Service unavailable");
                return;
            }
        }

        ManagedProcess target = selectProcess(exchange);
        if (target == null) {
            exchange.setStatusCode(503);
            exchange.getResponseSender().send("No available process");
            return;
        }

        UpstreamConnection conn = target.connection();
        if (conn == null) {
            exchange.setStatusCode(502);
            exchange.getResponseSender().send("Internal routing error");
            return;
        }
        forwarder.forwardTo(new UpstreamTarget(conn.connectUri(), conn.ignoreCertificates()));
    }

    private ManagedProcess selectProcess(HttpServerExchange exchange) {
        List<ManagedProcess> list = new ArrayList<>(processList);
        if (list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);

        // Build fingerprint
        String ip = ResolvedClientIp.get(exchange);
        String ua = exchange.getRequestHeaders().getFirst(Headers.USER_AGENT);
        String al = exchange.getRequestHeaders().getFirst(new HttpString("Accept-Language"));
        String fingerprint = (ip != null ? ip : "") + (ua != null ? ua : "") + (al != null ? al : "");

        // Check if fingerprint is pinned to an existing process
        for (ManagedProcess proc : list) {
            if (!proc.isIsolated() && proc.hasFingerprint(fingerprint)) {
                proc.pinFingerprint(fingerprint); // refresh TTL
                return proc;
            }
        }

        // Random selection, skipping isolated and overloaded processes
        int start = ThreadLocalRandom.current().nextInt(list.size());
        for (int i = 0; i < list.size(); i++) {
            ManagedProcess proc = list.get((start + i) % list.size());
            if (!proc.isIsolated() && proc.cpuPercent() < ROUTING_CPU_CEILING) {
                proc.pinFingerprint(fingerprint);
                return proc;
            }
        }

        // Fallback: any non-isolated process
        for (ManagedProcess proc : list) {
            if (!proc.isIsolated()) return proc;
        }

        return null;
    }

    // -----------------------------------------------------------------------
    // Health and info
    // -----------------------------------------------------------------------

    @Override
    public int getSiteId() { return siteId; }

    @Override
    public SiteHealth getHealth() {
        if (readyCount.get() > 0) return SiteHealth.UP;
        if (runningCount.get() > 0) return SiteHealth.DEGRADED;
        return SiteHealth.DOWN;
    }

    public int activeProcessCount() {
        int count = 0;
        for (ManagedProcess proc : processList) {
            if (!proc.isIsolated()) count++;
        }
        return count;
    }

    /** All spawned, still-running children (ready or not, isolated or not). */
    public int runningProcessCount() {
        return runningCount.get();
    }

    /** Spawns requested but not yet concluded ready-or-dead (capacity still booting). */
    public int requestedProcessCount() {
        return requestedCount.get();
    }

    /** Persist every live process's rolling log immediately (the periodic flush, on demand). */
    public void flushProclogsNow() {
        flushProclogs();
    }

    public List<ManagedProcess> getProcesses() {
        return Collections.unmodifiableList(new ArrayList<>(processList));
    }

    public ManagedProcess getProcess(long pid) {
        return processMap.get(pid);
    }

    /**
     * Every child pid this handler still holds, ready or not.
     *
     * <p>{@link ProcessReaper} asks THIS rather than {@link #getProcesses()}: a child that
     * has not signalled ready yet is still a child of the current controller generation,
     * and reaping it would kill a healthy workload mid-boot.
     */
    public Set<Long> livePids() {
        return Set.copyOf(processMap.keySet());
    }

    /**
     * The parent-side IPC channel for a given child pid, or null if the
     * child never connected (happens for non-Alchemy Node sites and any
     * custom command-type children without a TCP shim).
     */
    public IpcChannel getIpcChannel(long pid) {
        return ipcChannels.get(pid);
    }

    private void handleApiRequest(HttpServerExchange exchange, String actions) {
        for (String action : actions.split(",")) {
            action = action.trim();
            if ("broadcast".equals(action)) {
                broadcast(Map.of("action", "broadcast"));
            }
        }

        exchange.setStatusCode(200);
        exchange.getResponseSender().send("OK");
    }

    /**
     * UPSERT this process's rolling log: the first call inserts the row and stores its id on
     * the process; later calls (periodic flush, exit persist) update that same row.
     */
    private void persistProclog(ManagedProcess managed) {
        try {
            String logText = managed.getLogText();
            if (logText == null || logText.isEmpty()) return;

            var ds = HohenheimDatabase.datasource();
            var model = Models.get(ProclogModel.class);

            Row row = null;
            Integer rowId = managed.proclogRowId();
            if (rowId != null) {
                row = model.findById(rowId);
            }
            if (row == null) {
                row = model.createEmptyRow();
                row.set(ProclogModel.SITE_ID, siteId);
                row.set(ProclogModel.PID, (int) managed.pid());
                row.set(ProclogModel.CREATED_AT, managed.startTime());
            }

            // AIDEV-NOTE: the log_html column name is historical. What is stored is the
            // child's stdout VERBATIM (raw ANSI text), and an anonymous visitor to a
            // proxied site dictates part of it (request path, User-Agent land in the
            // child's access log). It is therefore UNTRUSTED TEXT and every reader must
            // treat it as text -- the proclog viewer renders it into a text node.
            row.set(ProclogModel.LOG_HTML, logText);
            row.set(ProclogModel.LINE_COUNT, countLines(logText));
            row.set(ProclogModel.SAVED_AT, Instant.now());
            model.save(row);
            managed.setProclogRowId(row.get(ProclogModel.ID));
        } catch (Exception e) {
            Blast.log("PROCESS: failed to persist proclog for", siteName, "pid=" + managed.pid(), "-", e.getMessage());
        }
    }

    private static int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) return;
        ScheduledFuture<?> flushTask = this.proclogFlushTask;
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        synchronized (lifecycleLock) {
            monitor.removeListener(siteId, this);
            for (IpcChannel ipc : ipcChannels.values()) {
                ipc.close();
            }
            ipcChannels.clear();
            remoteCache.clear();
            for (ManagedProcess proc : processMap.values()) {
                proc.kill();
                releaseUpstream(proc.port(), proc.socketPath(), proc.connection(),
                    proc.takeBooking());
            }
            processMap.clear();
            processList.clear();
            readyCount.set(0);
            runningCount.set(0);
            requestedCount.set(0);
        }
    }
}
