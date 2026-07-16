package be.elevenways.hohenheim.server.process;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.notification.NotificationService;
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
    protected final Set<String> apiKeys;

    // Process state
    private final CopyOnWriteArrayList<ManagedProcess> processList = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, ManagedProcess> processMap = new ConcurrentHashMap<>();
    private final AtomicInteger readyCount = new AtomicInteger(0);
    private final AtomicInteger runningCount = new AtomicInteger(0);

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

    protected ManagedProcessSiteHandler(int siteId, String siteName, Map<String, Object> settings,
                                         PortAllocator portAllocator, ProcessMonitor monitor) {
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

        // Parse API keys (stored as a JSON list of strings)
        this.apiKeys = new LinkedHashSet<>();
        Object apiKeysObj = settings.get("api_keys");
        if (apiKeysObj instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) continue;
                String key = item.toString().trim();
                if (!key.isEmpty()) apiKeys.add(key);
            }
        }

        // Parse environment variables from settings
        this.environmentVariables = EnvVars.toMap(settings.get("environment_variables"));

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
        return DatabaseEnvInjection.envForSite(siteId);
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
        long backoffMs = 150;
        requestedCount.incrementAndGet();

        boolean spawned = false;
        try {
            for (int attempt = 1; attempt <= MAX_EADDRINUSE_RETRIES; attempt++) {
                ManagedProcess managed = startProcessOnce();

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

    /** Alert the notification channels about a crash loop, at most once per interval. */
    private void notifyCrashLoop(long now) {
        long previous = lastCrashLoopNotified.get();
        if (now - previous < CRASH_NOTIFY_INTERVAL_MS
            || !lastCrashLoopNotified.compareAndSet(previous, now)) {
            return;
        }
        try {
            new NotificationService().send(NotificationEvents.PROCESS_CRASH_LOOP,
                "Crash loop: " + siteName,
                "Processes of site '" + siteName + "' keep exiting shortly after start;"
                    + " restarts are being throttled. Check the process logs.");
        } catch (Exception e) {
            Blast.log("PROCESS: could not send crash-loop notification -", e.getMessage());
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

        List<String> command = buildCommand(listenTarget);
        File workDir = getWorkingDirectory();

        IpcChannel ipc = null;
        UpstreamConnection connection = null;
        try {
            // Create IPC channel before spawning so the port is ready
            ipc = new IpcChannel();
            ipc.startAccepting();

            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null && workDir.exists()) {
                pb.directory(workDir);
            }
            pb.redirectErrorStream(false);

            Map<String, String> env = pb.environment();
            env.put("PORT", listenTarget);
            if (socketMode) {
                env.put("PATH_TO_SOCKET", socketPath);
            }
            env.put("HOHENHEIM_IPC_PORT", String.valueOf(ipc.getPort()));
            // Attached-database credentials resolve fresh per spawn (live published port);
            // operator-authored variables come after so an explicit value always wins.
            env.putAll(resolveInjectedEnvironment());
            env.putAll(environmentVariables);
            env.putAll(buildRuntimeEnvironment(port));

            SystemUsers.RunAsUser runAs = getRunAsUser();
            if (runAs != null) {
                List<String> suCommand = new ArrayList<>();
                suCommand.add("sudo");
                suCommand.add("-u");
                suCommand.add("#" + runAs.uid());
                if (runAs.gid() != null) {
                    suCommand.add("-g");
                    suCommand.add("#" + runAs.gid());
                }
                suCommand.add("--");
                suCommand.addAll(command);
                pb.command(suCommand);
                // The child must not inherit the daemon's HOME (os.homedir() would
                // resolve to the wrong user); point it at the target user's own home.
                if (runAs.home() != null && !runAs.home().isBlank()) {
                    env.put("HOME", runAs.home());
                } else {
                    env.remove("HOME");
                }
            } else {
                warnIfInheritingRoot();
            }

            Process process = pb.start();
            ManagedProcess managed = new ManagedProcess(process, port, socketPath, siteId);
            captureProcessOutput(managed);

            if (process.waitFor(STARTUP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                ipc.close();
                releaseUpstream(port, socketPath, null);

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

            Blast.log("PROCESS: started", siteName, "pid=" + process.pid(), "target=" + listenTarget);
            return managed;

        } catch (Exception e) {
            if (ipc != null) ipc.close();
            releaseUpstream(port, socketPath, connection);
            Blast.log("PROCESS: failed to start", siteName, "-", e.getMessage());
            return null;
        }
    }

    /** Release a process's upstream resources: TCP port, socket file, and bridge connection. */
    private void releaseUpstream(int port, String socketPath, UpstreamConnection connection) {
        if (port > 0) {
            portAllocator.release(port);
        }
        if (socketPath != null) {
            releaseSocketPath(socketPath);
        }
        if (connection != null) {
            connection.close();
        }
    }

    private void captureProcessOutput(ManagedProcess managed) {
        Thread.startVirtualThread(() -> pumpStream(managed.process().getInputStream(), managed, false));
        Thread.startVirtualThread(() -> pumpStream(managed.process().getErrorStream(), managed, true));
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
                    Blast.log("PROCESS: child error via IPC for", siteName,
                        "pid=" + proc.pid(), "-", msg.get("code"));
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
        releaseUpstream(port, managed.socketPath(), managed.connection());
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
        // Check for API key control requests
        if (!apiKeys.isEmpty()) {
            String key = exchange.getRequestHeaders().getFirst(X_HOHENHEIM_KEY);
            String actions = exchange.getRequestHeaders().getFirst(X_HOHENHEIM_ACTION);

            if (key != null && actions != null && apiKeys.contains(key)) {
                handleApiRequest(exchange, actions);
                return;
            }
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
        String ip = exchange.getRequestHeaders().getFirst(new HttpString("X-Forwarded-For"));
        if (ip == null) ip = exchange.getRequestHeaders().getFirst(new HttpString("X-Real-IP"));
        if (ip == null) ip = exchange.getSourceAddress().getAddress().getHostAddress();
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

            // Column is still LOG_HTML but the payload is raw ANSI text; the
            // proclog viewer feeds it through ghostty-web just like the live view.
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
        ScheduledFuture<?> flushTask = this.proclogFlushTask;
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        monitor.removeListener(siteId, this);
        for (IpcChannel ipc : ipcChannels.values()) {
            ipc.close();
        }
        ipcChannels.clear();
        remoteCache.clear();
        for (ManagedProcess proc : processMap.values()) {
            proc.kill();
            releaseUpstream(proc.port(), proc.socketPath(), proc.connection());
        }
        processMap.clear();
        processList.clear();
        readyCount.set(0);
        runningCount.set(0);
    }
}
