package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.server.sitetype.UpstreamConnection;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A single managed child process with monitoring state.
 * Thread-safe -- accessed from the monitoring thread and request threads.
 */
public class ManagedProcess {

    private final Process process;
    private final int port;
    private final String socketPath;
    private final int siteId;
    private final Instant startTime;

    // Monitoring state (updated by ProcessMonitor)
    private volatile int cpuPercent;
    private volatile long memoryKb;

    // Scaling state
    private final AtomicLong startOverload = new AtomicLong(0);
    private final AtomicLong startIdle = new AtomicLong(0);

    // Isolation
    private volatile boolean isolated;

    // Ready state
    private volatile boolean ready;

    // Startup diagnostics
    private volatile boolean addressInUse;

    // Upstream transport (TCP to the process port, or a unix-socket bridge). Set once the process
    // survives the startup grace; closed on exit/destroy.
    private volatile UpstreamConnection connection;

    // Fingerprint cache: fingerprint string -> true (with idle expiry managed externally)
    private final ConcurrentHashMap<String, Long> fingerprints = new ConcurrentHashMap<>();

    // Stdout/stderr capture (rolling buffer, raw UTF-8 including ANSI escapes).
    // Fed by ManagedProcessSiteHandler; consumed by the process-terminal WebSocket
    // (replayed on open) and by the proclog persister on process exit.
    private final StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_CHARS = 256 * 1024;
    private static final long FINGERPRINT_TTL_MS = 3600_000;

    // Live log listeners (for terminal WebSocket connections).
    // Each listener receives the exact raw chunk that was appended -- no transformation.
    private final Set<Consumer<String>> logListeners = new CopyOnWriteArraySet<>();

    public ManagedProcess(Process process, int port, String socketPath, int siteId) {
        this.process = process;
        this.port = port;
        this.socketPath = socketPath;
        this.siteId = siteId;
        this.startTime = Instant.now();
        this.isolated = false;
        this.ready = false;
    }

    public long pid() { return process.pid(); }
    public int port() { return port; }
    public String socketPath() { return socketPath; }
    public int siteId() { return siteId; }
    public Process process() { return process; }
    public Instant startTime() { return startTime; }
    public boolean isAlive() { return process.isAlive(); }

    public int cpuPercent() { return cpuPercent; }
    public long memoryKb() { return memoryKb; }

    public boolean isIsolated() { return isolated; }
    public void setIsolated(boolean isolated) { this.isolated = isolated; }

    public boolean isReady() { return ready; }
    public void setReady(boolean ready) { this.ready = ready; }

    public boolean hasAddressInUse() { return addressInUse; }
    public void markAddressInUse() { this.addressInUse = true; }

    public UpstreamConnection connection() { return connection; }
    public void setConnection(UpstreamConnection connection) { this.connection = connection; }

    public ConcurrentHashMap<String, Long> fingerprints() { return fingerprints; }

    public AtomicLong startOverload() { return startOverload; }
    public AtomicLong startIdle() { return startIdle; }

    /**
     * Package-private: called by ProcessMonitor to update stats.
     */
    void setCpuAndMem(int cpu, long mem) {
        this.cpuPercent = cpu;
        this.memoryKb = mem;
    }

    /**
     * Append a raw chunk of stdout/stderr to the rolling buffer and fan it out
     * to any live terminal listeners verbatim (no escaping, ANSI preserved).
     * The buffer is capped in characters; the oldest head is trimmed first.
     */
    public void appendLog(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        synchronized (logBuffer) {
            logBuffer.append(chunk);
            int overflow = logBuffer.length() - MAX_LOG_CHARS;
            if (overflow > 0) {
                logBuffer.delete(0, overflow);
            }
        }
        for (Consumer<String> listener : logListeners) {
            listener.accept(chunk);
        }
    }

    public void addLogListener(Consumer<String> listener) {
        logListeners.add(listener);
    }

    public void removeLogListener(Consumer<String> listener) {
        logListeners.remove(listener);
    }

    /**
     * Snapshot of the rolling log (raw text, may contain ANSI escapes).
     */
    public String getLogText() {
        synchronized (logBuffer) {
            return logBuffer.toString();
        }
    }

    /**
     * Kill the process.
     */
    public void kill() {
        process.destroyForcibly();
    }

    /**
     * Check if a fingerprint is pinned to this process.
     */
    public boolean hasFingerprint(String fingerprint) {
        Long timestamp = fingerprints.get(fingerprint);
        if (timestamp == null) return false;
        // 1 hour idle TTL
        if (System.currentTimeMillis() - timestamp > FINGERPRINT_TTL_MS) {
            fingerprints.remove(fingerprint);
            return false;
        }
        return true;
    }

    /**
     * Pin a fingerprint to this process.
     */
    public void pinFingerprint(String fingerprint) {
        fingerprints.put(fingerprint, System.currentTimeMillis());
    }

    /**
     * Count active (non-expired) fingerprints.
     */
    public int activeFingerprintCount() {
        long cutoff = System.currentTimeMillis() - FINGERPRINT_TTL_MS;
        fingerprints.entrySet().removeIf(e -> e.getValue() < cutoff);
        return fingerprints.size();
    }
}
