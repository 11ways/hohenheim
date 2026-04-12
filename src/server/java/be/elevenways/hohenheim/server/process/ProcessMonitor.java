package be.elevenways.hohenheim.server.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    // Per-site listeners for stats updates
    private final ConcurrentHashMap<Integer, Set<StatsListener>> listenersBySite = new ConcurrentHashMap<>();

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

    public void addListener(int siteId, StatsListener listener) {
        if (listener != null) {
            listenersBySite.computeIfAbsent(siteId, k -> new CopyOnWriteArraySet<>()).add(listener);
        }
    }

    public void removeListener(int siteId, StatsListener listener) {
        if (listener != null) {
            listenersBySite.computeIfPresent(siteId, (k, set) -> {
                set.remove(listener);
                return set.isEmpty() ? null : set;
            });
        }
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
                // Aggregate across the pid and any descendants. The managed
                // pid is our Node fork-wrapper, which is nearly idle; the
                // real workload runs in the forked child and its workers.
                Set<Long> tree = collectProcessTree(pid);
                int cpu = 0;
                long mem = 0;
                for (Long tpid : tree) {
                    cpu += sampleCpu(tpid);
                    mem += sampleMemory(tpid);
                }
                if (cpu > 100) cpu = 100;

                // Update process state (accessed via reflection-free field setting)
                // We use the package-private updateStats pattern
                setCpuAndMem(proc, cpu, mem);

                Set<StatsListener> siteListeners = listenersBySite.get(proc.siteId());
                if (siteListeners != null) {
                    for (StatsListener listener : siteListeners) {
                        listener.onStats(proc, cpu, mem);
                    }
                }
            } catch (Exception e) {
                // Process may have exited (NoSuchFileException) or transient /proc parsing issue
            }
        }
    }

    /**
     * Breadth-first walk of /proc/{pid}/task/{tid}/children, capped at
     * MAX_TREE_DEPTH to avoid runaway in pathological cases. Returns the
     * original pid plus every live descendant found.
     */
    private Set<Long> collectProcessTree(long rootPid) {
        Set<Long> out = new HashSet<>();
        out.add(rootPid);
        collectDescendants(rootPid, out, 0);
        return out;
    }

    private static final int MAX_TREE_DEPTH = 8;

    private void collectDescendants(long pid, Set<Long> out, int depth) {
        if (depth >= MAX_TREE_DEPTH) return;
        Path taskDir = Path.of("/proc/" + pid + "/task");
        try (var tids = Files.list(taskDir)) {
            tids.forEach(taskPath -> {
                Path childrenFile = taskPath.resolve("children");
                try {
                    String children = Files.readString(childrenFile).trim();
                    if (children.isEmpty()) return;
                    for (String tok : children.split("\\s+")) {
                        try {
                            long childPid = Long.parseLong(tok);
                            if (out.add(childPid)) {
                                collectDescendants(childPid, out, depth + 1);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } catch (Exception ignored) {
                    // Task exited mid-read or /proc entry vanished
                }
            });
        } catch (Exception ignored) {
            // Root process exited
        }
    }

    private int sampleCpu(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
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
            for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
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
