package be.elevenways.hohenheim.server.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

/**
 * Monitors child process CPU and memory via /proc/{pid}/stat.
 * Runs on a shared scheduled executor, sampling every 10 seconds.
 */
public class ProcessMonitor {

    private static final long SAMPLE_INTERVAL_MS = 10_000;
    private static final long CLOCK_TICKS_PER_SEC;

    static {
        // Linux CLK_TCK is almost always 100
        long ticks = 100;
        try {
            var pb = new ProcessBuilder("getconf", "CLK_TCK");
            var p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            ticks = Long.parseLong(out);
        } catch (Exception e) {
            // Fall back to 100
        }
        CLOCK_TICKS_PER_SEC = ticks;
    }

    private final ScheduledExecutorService scheduler;

    // Per-PID previous CPU ticks for delta calculation
    private final ConcurrentHashMap<Long, long[]> previousTicks = new ConcurrentHashMap<>();

    // Registered processes
    private final ConcurrentHashMap<Long, ManagedProcess> processes = new ConcurrentHashMap<>();

    // Listener for stats updates
    private volatile StatsListener listener;

    public interface StatsListener {
        void onStats(ManagedProcess proc, int cpuPercent, long memoryKb);
    }

    public ProcessMonitor() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "process-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sample, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public void setListener(StatsListener listener) {
        this.listener = listener;
    }

    public void register(ManagedProcess proc) {
        processes.put(proc.pid(), proc);
    }

    public void unregister(long pid) {
        processes.remove(pid);
        previousTicks.remove(pid);
    }

    private void sample() {
        for (var entry : processes.entrySet()) {
            long pid = entry.getKey();
            ManagedProcess proc = entry.getValue();

            if (!proc.isAlive()) {
                processes.remove(pid);
                previousTicks.remove(pid);
                continue;
            }

            try {
                int cpu = sampleCpu(pid);
                long mem = sampleMemory(pid);

                // Update process state (accessed via reflection-free field setting)
                // We use the package-private updateStats pattern
                setCpuAndMem(proc, cpu, mem);

                StatsListener l = listener;
                if (l != null) l.onStats(proc, cpu, mem);
            } catch (Exception e) {
                // Process may have exited
            }
        }
    }

    private int sampleCpu(long pid) {
        try {
            Path statPath = Path.of("/proc/" + pid + "/stat");
            if (!Files.exists(statPath)) return 0;

            String stat = Files.readString(statPath);
            // Skip past "(comm)" section which may contain spaces
            int closeParen = stat.lastIndexOf(')');
            String[] fields = stat.substring(closeParen + 2).split(" ");
            // fields[11] = utime, fields[12] = stime (0-indexed)
            long utime = Long.parseLong(fields[11]);
            long stime = Long.parseLong(fields[12]);
            long totalTicks = utime + stime;

            long now = System.nanoTime();
            long[] prev = previousTicks.get(pid);

            if (prev == null) {
                previousTicks.put(pid, new long[]{totalTicks, now});
                return 0;
            }

            long tickDelta = totalTicks - prev[0];
            double timeDelta = (now - prev[1]) / 1_000_000_000.0; // seconds

            previousTicks.put(pid, new long[]{totalTicks, now});

            if (timeDelta <= 0) return 0;
            double cpuPercent = (tickDelta / (CLOCK_TICKS_PER_SEC * timeDelta)) * 100.0;
            return (int) Math.min(100, Math.max(0, cpuPercent));
        } catch (Exception e) {
            return 0;
        }
    }

    private long sampleMemory(long pid) {
        try {
            Path statusPath = Path.of("/proc/" + pid + "/status");
            if (!Files.exists(statusPath)) return 0;

            for (String line : Files.readAllLines(statusPath)) {
                if (line.startsWith("VmRSS:")) {
                    String kb = line.substring(6).trim().replace(" kB", "").trim();
                    return Long.parseLong(kb);
                }
            }
        } catch (Exception e) {
            // Process may have exited
        }
        return 0;
    }

    /**
     * Set CPU and memory on the process object.
     * Uses package-private access since ManagedProcess is in the same package.
     */
    private void setCpuAndMem(ManagedProcess proc, int cpu, long mem) {
        // Direct field access via package-private setter
        proc.setCpuAndMem(cpu, mem);
    }
}
