package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.protoblast.common.Blast;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
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
    protected final Map<String, String> environmentVariables;
    protected final Set<String> apiKeys;

    // Process state
    private final CopyOnWriteArrayList<ManagedProcess> processList = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, ManagedProcess> processMap = new ConcurrentHashMap<>();
    private final AtomicInteger readyCount = new AtomicInteger(0);
    private final AtomicInteger runningCount = new AtomicInteger(0);

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

    // Startup queue: requests waiting for the first process to be ready
    private final CountDownLatch firstReadyLatch = new CountDownLatch(1);

    // Scaling constants
    private static final int SCALE_UP_CPU_THRESHOLD = 50;
    private static final long SCALE_UP_SUSTAIN_MS = 15_000;
    private static final int SCALE_UP_HARD_CAP = 5;
    private static final long SCALE_DOWN_IDLE_MS = 180_000;
    private static final int SCALE_DOWN_CPU_THRESHOLD = 0;
    private static final int ROUTING_CPU_CEILING = 92;

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
        this.waitForReady = Boolean.TRUE.equals(settings.get("wait_for_ready"));

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
        this.environmentVariables = new LinkedHashMap<>();
        Object envVars = settings.get("environment_variables");
        if (envVars instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object nameObj = map.get("name");
                    Object valueObj = map.get("value");
                    if (nameObj instanceof String name && !name.isEmpty()) {
                        String value = valueObj instanceof String v ? v : "";
                        environmentVariables.put(name, value);
                    }
                }
            }
        }

        monitor.addListener(siteId, this);
    }

    // -----------------------------------------------------------------------
    // Abstract: subclasses define what command to spawn
    // -----------------------------------------------------------------------

    /**
     * Build the command to execute (e.g., ["node", "app.js", "--port=3000"]).
     * The port is already allocated and passed as a parameter.
     */
    protected abstract List<String> buildCommand(int port);

    /**
     * Build additional environment variables specific to the runtime.
     * The base environment (PORT, system env) is already set.
     */
    protected abstract Map<String, String> buildRuntimeEnvironment(int port);

    /**
     * The working directory for the child process.
     */
    protected abstract File getWorkingDirectory();

    /**
     * Optional: UID to run the process as (0 = current user).
     */
    protected int getUid() { return 0; }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Start the minimum number of processes.
     */
    public void startMinimumServers() {
        long now = System.currentTimeMillis();
        long prev = lastStartMinimumServers.get();
        if (now - prev < 500 || !lastStartMinimumServers.compareAndSet(prev, now)) return;

        int active = activeProcessCount();
        for (int i = active; i < minProcesses; i++) {
            startProcess();
        }
    }

    /**
     * Spawn a single new child process.
     */
    public ManagedProcess startProcess() {
        long backoffMs = 150;

        for (int attempt = 1; attempt <= MAX_EADDRINUSE_RETRIES; attempt++) {
            ManagedProcess managed = startProcessOnce();

            if (managed != null) {
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
    }

    private ManagedProcess startProcessOnce() {
        int port;
        try {
            port = portAllocator.allocate(siteId);
        } catch (Exception e) {
            Blast.log("PROCESS: failed to allocate port for", siteName, "-", e.getMessage());
            return null;
        }

        List<String> command = buildCommand(port);
        File workDir = getWorkingDirectory();

        IpcChannel ipc = null;
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
            env.put("PORT", String.valueOf(port));
            env.put("HOHENHEIM_IPC_PORT", String.valueOf(ipc.getPort()));
            env.putAll(environmentVariables);
            env.putAll(buildRuntimeEnvironment(port));

            int uid = getUid();
            if (uid > 0) {
                List<String> suCommand = new ArrayList<>();
                suCommand.add("sudo");
                suCommand.add("-u");
                suCommand.add("#" + uid);
                suCommand.add("--");
                suCommand.addAll(command);
                pb.command(suCommand);
            }

            Process process = pb.start();
            ManagedProcess managed = new ManagedProcess(process, port, null, siteId);
            captureProcessOutput(managed);

            if (process.waitFor(STARTUP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                ipc.close();
                portAllocator.release(port);

                if (managed.hasAddressInUse()) {
                    Blast.log("PROCESS: retrying", siteName, "after EADDRINUSE on port=" + port);
                } else {
                    Blast.log("PROCESS: failed to start", siteName,
                        "exit=" + process.exitValue(), "port=" + port);
                }

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

            Blast.log("PROCESS: started", siteName, "pid=" + process.pid(), "port=" + port);
            return managed;

        } catch (Exception e) {
            if (ipc != null) ipc.close();
            portAllocator.release(port);
            Blast.log("PROCESS: failed to start", siteName, "-", e.getMessage());
            return null;
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

    private void markProcessReady(ManagedProcess managed) {
        if (managed.isReady()) return;
        managed.setReady(true);
        processList.add(managed);
        readyCount.incrementAndGet();
        firstReadyLatch.countDown();
    }

    // -----------------------------------------------------------------------
    // IPC message handling
    // -----------------------------------------------------------------------

    private void handleIpcMessage(ManagedProcess proc, IpcChannel ipc, Map<String, Object> msg) {
        String type = msg.get("type") instanceof String s ? s : "";
        switch (type) {
            case "ready" -> {
                if (waitForReady) {
                    markProcessReady(proc);
                    Blast.log("PROCESS: ready signal from", siteName, "pid=" + proc.pid());
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
        portAllocator.release(port);
        processMap.remove(pid);
        processList.remove(managed);

        runningCount.updateAndGet(v -> Math.max(0, v - 1));

        if (managed.isReady()) {
            readyCount.updateAndGet(v -> Math.max(0, v - 1));
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

        // Wait for at least one process to be ready
        if (readyCount.get() == 0) {
            startMinimumServers();
            try {
                if (!firstReadyLatch.await(60, TimeUnit.SECONDS)) {
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

        URI upstream;
        try {
            upstream = new URI("http", null, "127.0.0.1", target.port(), "/", null, null);
        } catch (Exception e) {
            exchange.setStatusCode(502);
            exchange.getResponseSender().send("Internal routing error");
            return;
        }

        forwarder.forwardTo(upstream);
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

    private void persistProclog(ManagedProcess managed) {
        try {
            String logText = managed.getLogText();
            if (logText == null || logText.isEmpty()) return;

            var ds = HohenheimDatabase.datasource();
            var model = new ProclogModel(ds);
            Row row = model.createEmptyRow();
            row.set(ProclogModel.SITE_ID, siteId);
            row.set(ProclogModel.PID, (int) managed.pid());
            // Column is still LOG_HTML but the payload is raw ANSI text; the
            // proclog viewer feeds it through ghostty-web just like the live view.
            row.set(ProclogModel.LOG_HTML, logText);
            row.set(ProclogModel.CREATED_AT, managed.startTime());
            model.save(row);
        } catch (Exception e) {
            Blast.log("PROCESS: failed to persist proclog for", siteName, "pid=" + managed.pid(), "-", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        monitor.removeListener(siteId, this);
        for (IpcChannel ipc : ipcChannels.values()) {
            ipc.close();
        }
        ipcChannels.clear();
        remoteCache.clear();
        for (ManagedProcess proc : processMap.values()) {
            proc.kill();
        }
        processMap.clear();
        processList.clear();
        readyCount.set(0);
        runningCount.set(0);
    }
}
