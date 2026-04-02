package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.protoblast.common.Blast;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base handler for site types that manage child processes.
 * Handles spawning, monitoring, auto-scaling, crash recovery,
 * and fingerprint-based sticky routing.
 *
 * Subclasses implement buildCommand() to define what to spawn.
 * This makes the base reusable for Node.js, Java, or any other runtime.
 */
public abstract class ManagedProcessSiteHandler implements SiteRequestHandler, ProcessMonitor.StatsListener {

    // Configuration
    protected final int siteId;
    protected final String siteName;
    protected final int minProcesses;
    protected final int maxProcesses;
    protected final boolean waitForReady;
    protected final Map<String, String> environmentVariables;

    // Process state
    private final CopyOnWriteArrayList<ManagedProcess> processList = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Long, ManagedProcess> processMap = new ConcurrentHashMap<>();
    private final AtomicInteger readyCount = new AtomicInteger(0);
    private final AtomicInteger runningCount = new AtomicInteger(0);

    // Port allocation
    private final PortAllocator portAllocator;

    // Monitoring
    private final ProcessMonitor monitor;

    // Crash recovery
    private final LinkedList<Long> exitLog = new LinkedList<>();
    private static final int MAX_EXIT_LOG = 20;
    private static final int CRASH_THRESHOLD_COUNT = 5;
    private static final long CRASH_BACKOFF_MS = 3000;

    // Debounce
    private volatile long lastStartMinimumServers;

    // Startup queue: requests waiting for the first process to be ready
    private final CountDownLatch firstReadyLatch = new CountDownLatch(1);
    private volatile boolean firstProcessStarted = false;

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

        // Parse environment variables from settings
        this.environmentVariables = new LinkedHashMap<>();
        Object envVars = settings.get("environment_variables");
        if (envVars instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    String name = String.valueOf(map.get("name"));
                    String value = String.valueOf(map.get("value"));
                    if (name != null && !name.isEmpty()) {
                        environmentVariables.put(name, value);
                    }
                }
            }
        }

        monitor.setListener(this);
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
        if (now - lastStartMinimumServers < 500) return;
        lastStartMinimumServers = now;

        int active = activeProcessCount();
        for (int i = active; i < minProcesses; i++) {
            startProcess();
        }
    }

    /**
     * Spawn a single new child process.
     */
    public ManagedProcess startProcess() {
        int port;
        try {
            port = portAllocator.allocate(siteId);
        } catch (Exception e) {
            Blast.log("PROCESS: failed to allocate port for", siteName, "-", e.getMessage());
            return null;
        }

        List<String> command = buildCommand(port);
        File workDir = getWorkingDirectory();

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (workDir != null && workDir.exists()) {
                pb.directory(workDir);
            }
            pb.redirectErrorStream(false);

            // Build environment
            Map<String, String> env = pb.environment();
            env.put("PORT", String.valueOf(port));
            env.putAll(environmentVariables);
            env.putAll(buildRuntimeEnvironment(port));

            // UID support via su wrapper
            int uid = getUid();
            if (uid > 0) {
                // Wrap command with su
                String cmdStr = String.join(" ", command);
                pb.command("su", "-s", "/bin/sh", "-c", cmdStr, String.valueOf(uid));
            }

            Process process = pb.start();
            ManagedProcess managed = new ManagedProcess(process, port, siteId);

            processMap.put(process.pid(), managed);
            runningCount.incrementAndGet();

            // If not waiting for ready signal, mark as ready immediately
            if (!waitForReady) {
                managed.setReady(true);
                readyCount.incrementAndGet();
                processList.add(managed);
                if (!firstProcessStarted) {
                    firstProcessStarted = true;
                    firstReadyLatch.countDown();
                }
            }

            // Register for monitoring
            monitor.register(managed);

            // Capture stdout in a background thread
            captureStdout(managed);

            // Watch for exit
            process.onExit().thenAccept(p -> processExit(managed));

            Blast.log("PROCESS: started", siteName, "pid=" + process.pid(), "port=" + port);
            return managed;

        } catch (Exception e) {
            portAllocator.release(port);
            Blast.log("PROCESS: failed to start", siteName, "-", e.getMessage());
            return null;
        }
    }

    private void captureStdout(ManagedProcess managed) {
        Thread.startVirtualThread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(managed.process().getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Simple HTML escaping (not ANSI-to-HTML yet, but functional)
                    String html = line.replace("&", "&amp;").replace("<", "&lt;")
                                      .replace(">", "&gt;");
                    managed.appendLog(html);
                }
            } catch (Exception e) {
                // Process ended
            }
        });
        // Also drain stderr to prevent blocking
        Thread.startVirtualThread(() -> {
            try { managed.process().getErrorStream().transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (Exception ignored) {}
        });
    }

    // -----------------------------------------------------------------------
    // Process exit and crash recovery
    // -----------------------------------------------------------------------

    private void processExit(ManagedProcess managed) {
        long pid = managed.pid();
        int port = managed.port();

        Blast.log("PROCESS: exited", siteName, "pid=" + pid);

        // Cleanup
        monitor.unregister(pid);
        portAllocator.release(port);
        processMap.remove(pid);
        processList.remove(managed);

        int running = runningCount.decrementAndGet();
        if (running < 0) runningCount.set(0);

        if (managed.isReady()) {
            int ready = readyCount.decrementAndGet();
            if (ready < 0) readyCount.set(0);
        }

        // Track exit for crash detection
        long now = System.currentTimeMillis();
        synchronized (exitLog) {
            exitLog.add(now);
            while (exitLog.size() > MAX_EXIT_LOG) exitLog.removeFirst();

            // Crash backoff: if >5 exits and mean interval is too short
            if (exitLog.size() > CRASH_THRESHOLD_COUNT) {
                long sum = 0;
                for (long ts : exitLog) sum += ts;
                long mean = sum / exitLog.size();
                long diff = now - mean;
                long threshold = 2500L * exitLog.size();

                if (diff < threshold) {
                    Blast.log("PROCESS: crash loop detected for", siteName,
                              "- waiting", CRASH_BACKOFF_MS, "ms before restart");
                    try { Thread.sleep(CRASH_BACKOFF_MS); } catch (InterruptedException ignored) {}
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
        if (proc.siteId() != this.siteId) return;

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
        // Wait for at least one process to be ready
        if (readyCount.get() == 0) {
            if (!firstProcessStarted) {
                startMinimumServers();
            }
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
        String ip = exchange.getRequestHeaders().getFirst(new io.undertow.util.HttpString("X-Forwarded-For"));
        if (ip == null) ip = exchange.getRequestHeaders().getFirst(new io.undertow.util.HttpString("X-Real-IP"));
        if (ip == null) ip = exchange.getSourceAddress().getAddress().getHostAddress();
        String ua = exchange.getRequestHeaders().getFirst(Headers.USER_AGENT);
        String al = exchange.getRequestHeaders().getFirst(new io.undertow.util.HttpString("Accept-Language"));
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

    @Override
    public void destroy() {
        for (ManagedProcess proc : processMap.values()) {
            proc.kill();
        }
        processMap.clear();
        processList.clear();
        readyCount.set(0);
        runningCount.set(0);
    }
}
