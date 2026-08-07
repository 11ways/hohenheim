package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THE memory/cpu cap for a HOST process: a cgroup v2 scope created through
 * {@code systemd-run}, wrapped around the existing setsid+sudo spawn.
 *
 * AIDEV-NOTE: THE design decision for the host-process tier's resource cap (2026-08-07),
 * and the reason it is a cgroup rather than an rlimit. {@code RLIMIT_AS} and
 * {@code RLIMIT_DATA} both bound ADDRESS SPACE, not resident memory: a JVM that reserves a
 * 2 GB heap and touches 200 MB of it dies under an rlimit sized to what it actually uses,
 * so an operator would have to declare a number that caps nothing. A cgroup's
 * {@code memory.max} is charged on FAULT, which is the number an operator can reason about
 * and the same number every container tier already books. The pids cap is TasksMax for the
 * same reason -- {@code RLIMIT_NPROC} is counted per UID, so it only means anything at all
 * because {@code WorkloadIdentity} makes the site uid exclusive, and it is kept as the
 * floor (see {@code SystemUsers.executionBuilder}) rather than as the cap.
 *
 * AIDEV-NOTE: the cap is REQUIRED WHERE IT IS DECLARED, and that is the whole invariant.
 * A site that declares {@code memory_limit_mb} on a host that cannot create a scope is
 * REFUSED by name -- it never spawns uncapped -- because {@code InstanceCapacity} books
 * exactly what it caps ({@code InstanceKindHandler.defaultFootprintMb}: "if a kind ever
 * charges one number and caps another, the gate is decoration again") and a process child
 * is booked on the same host budget. A site that declares NO limit is capped by nothing and
 * BOOKS nothing: it stays the tier's pre-existing unbounded shape, warned about once per
 * handler, never charged against a budget it cannot honour. Declaring nothing is not a way
 * to get free capacity -- it is a way to get no accounting, which is visible.
 *
 * AIDEV-NOTE: this is a NEW HOST DEPENDENCY (systemd) and, on a root daemon, nothing more;
 * on the ordinary non-root deployment it needs the daemon user's own systemd USER manager
 * ({@code loginctl enable-linger hohenheim}), which is deliberately NOT a new sudo grant --
 * {@code sudo systemd-run} would be arbitrary code as root, an enormously wider grant than
 * the deployed {@code NOPASSWD: /usr/bin/nft} line, to gain a knob the user manager already
 * delegates. See docs/deploy-native.md. The user manager is reached over its bus, so
 * XDG_RUNTIME_DIR must survive into the systemd-run invocation and must NOT survive into
 * the child: {@link #scopePrefix} strips it again with {@code env -u} before the privilege
 * drop, so a site child never learns where the daemon user's bus lives.
 */
public final class ProcessConfinement {

    /** How (and whether) this host can put a child process in a resource-capped cgroup. */
    public enum Mode {
        /** The daemon is root: a system-manager transient scope. */
        SYSTEM_SCOPE,
        /** The daemon's own systemd user manager holds a delegated cgroup subtree. */
        USER_SCOPE,
        /** No enforceable cap on this host. */
        NONE
    }

    /** The probe's verdict: the usable mode, and when NONE, why -- BY NAME. */
    public record Availability(@NonNull Mode mode, @Nullable String reason) {

        public boolean enforceable() {
            return this.mode != Mode.NONE;
        }
    }

    private static final String SYSTEMD_RUN = "/usr/bin/systemd-run";
    private static final String ENV = "/usr/bin/env";

    /** Bus-reaching variables systemd-run needs and the child may never see. */
    private static final List<String> BUS_VARS =
        List.of("XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS");

    /** Fallback per-child process cap when the setting is unreadable; also its default. */
    public static final int DEFAULT_PIDS_LIMIT = 512;

    private static final long PROBE_TIMEOUT_SECONDS = 10;

    private static final AtomicLong SCOPE_SEQUENCE = new AtomicLong();

    private static volatile @Nullable Availability cached;

    private ProcessConfinement() {
    }

    /**
     * Whether this host can enforce a memory cap on a child process, PROVEN by creating a
     * real capped scope once and reading the cap back out of the kernel -- never inferred
     * from the presence of a binary. Cached for the life of the JVM.
     */
    public static @NonNull Availability availability() {
        Availability known = cached;
        if (known != null) {
            return known;
        }
        synchronized (ProcessConfinement.class) {
            if (cached == null) {
                cached = probe();
                if (cached.enforceable()) {
                    Blast.log("PROCESS: resource caps are enforceable through",
                        cached.mode() == Mode.SYSTEM_SCOPE
                            ? "systemd system scopes" : "the daemon's systemd user manager");
                } else {
                    Blast.log("PROCESS: resource caps are NOT enforceable on this host -",
                        cached.reason());
                }
            }
            return cached;
        }
    }

    /** Test seam: forget the probed verdict (and optionally pin one). */
    public static void overrideForTest(@Nullable Availability availability) {
        synchronized (ProcessConfinement.class) {
            cached = availability;
        }
    }

    /**
     * The refusal text a caller shows when a declared cap cannot be enforced here.
     * Names the host, the mechanism and what the operator must do -- the same shape as
     * the network-policy refusal every container tier already carries.
     */
    public static @NonNull String unenforceableRefusal(@NonNull String siteName) {
        return "site '" + siteName + "' declares a memory limit, but this host cannot enforce"
            + " one: " + availability().reason() + ". A declared limit is booked against the"
            + " host's memory budget, so a limit that is not enforced would be a paper limit"
            + " -- the spawn is refused instead. Install systemd and, for a non-root daemon,"
            + " give it a systemd user manager (loginctl enable-linger), or clear"
            + " memory_limit_mb to run this site unbounded and unbooked.";
    }

    /**
     * The command prefix that puts the spawn in a capped cgroup scope, or an empty list
     * when nothing is capped.
     *
     * @param label     a short workload name, folded into the transient unit name
     * @param memoryMb  the memory cap in MiB, or null/non-positive for no memory cap
     * @param cpus      the cpu cap in cores (1.5 = 150%), or null for no cpu cap
     * @throws IllegalStateException when a cap is asked for on a host that cannot enforce it
     */
    public static @NonNull List<String> scopePrefix(@NonNull String label,
                                                    @Nullable Integer memoryMb,
                                                    @Nullable Double cpus) {
        boolean capsMemory = memoryMb != null && memoryMb > 0;
        boolean capsCpu = cpus != null && cpus > 0;
        if (!capsMemory && !capsCpu) {
            return List.of();
        }
        Availability availability = availability();
        if (!availability.enforceable()) {
            throw new IllegalStateException(unenforceableRefusal(label));
        }

        List<String> prefix = new ArrayList<>();
        prefix.add(SYSTEMD_RUN);
        if (availability.mode() == Mode.USER_SCOPE) {
            prefix.add("--user");
        }
        prefix.add("--scope");
        // Quiet: systemd-run announces the unit on stderr, and this tier's stderr is the
        // child's proclog AND the EADDRINUSE scan's input.
        prefix.add("--quiet");
        // Collect: the transient unit is garbage-collected on exit, so a crash-looping
        // site does not leave one failed unit per exit behind.
        prefix.add("--collect");
        prefix.add("--unit=" + unitName(label));
        if (capsMemory) {
            prefix.add("-p");
            prefix.add("MemoryMax=" + memoryMb + "M");
            // Without this the cap is evaded by swapping: memory.max only bounds RAM.
            prefix.add("-p");
            prefix.add("MemorySwapMax=0");
        }
        if (capsCpu) {
            prefix.add("-p");
            prefix.add("CPUQuota=" + Math.round(cpus * 100) + "%");
        }
        prefix.add("-p");
        prefix.add("TasksMax=" + pidsLimit());
        prefix.add("--");
        if (availability.mode() == Mode.USER_SCOPE) {
            // systemd-run has read the bus address by now; nothing below may see it.
            prefix.add(ENV);
            for (String variable : BUS_VARS) {
                prefix.add("-u");
                prefix.add(variable);
            }
            prefix.add("--");
        }
        return List.copyOf(prefix);
    }

    /**
     * Variables the spawn's explicit environment must carry for {@link #scopePrefix} to
     * reach the manager; empty unless a user-manager scope is what will be used.
     */
    public static void contributeEnvironment(@NonNull Map<String, String> environment) {
        if (availability().mode() != Mode.USER_SCOPE) {
            return;
        }
        for (String variable : BUS_VARS) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                environment.put(variable, value);
            }
        }
    }

    /** @return the configured per-child process cap, never below 1 */
    public static int pidsLimit() {
        Integer configured = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Process.PIDS_LIMIT);
        return configured != null && configured > 0 ? configured : DEFAULT_PIDS_LIMIT;
    }

    // -- the probe ------------------------------------------------------------

    private static @NonNull Availability probe() {
        if (!new File(SYSTEMD_RUN).canExecute()) {
            return new Availability(Mode.NONE, SYSTEMD_RUN + " is not installed");
        }
        Mode mode = SystemUsers.daemonRunsAsRoot() ? Mode.SYSTEM_SCOPE : Mode.USER_SCOPE;
        String failure = probeScope(mode);
        if (failure == null) {
            return new Availability(mode, null);
        }
        if (mode == Mode.USER_SCOPE) {
            return new Availability(Mode.NONE, "the daemon user has no usable systemd user"
                + " manager (" + failure + "); run 'loginctl enable-linger' for it, or run"
                + " the controller as root");
        }
        return new Availability(Mode.NONE, "systemd refused a transient scope (" + failure + ")");
    }

    /**
     * Create one real capped scope and make the kernel answer for it: the probe child
     * reads its OWN {@code memory.max} back and it must equal what was asked for.
     * A scope that is created but not enforced is exactly the paper limit being refused.
     */
    private static @Nullable String probeScope(@NonNull Mode mode) {
        List<String> command = new ArrayList<>();
        command.add(SYSTEMD_RUN);
        if (mode == Mode.USER_SCOPE) {
            command.add("--user");
        }
        command.add("--scope");
        command.add("--quiet");
        command.add("--collect");
        command.add("--unit=" + unitName("probe"));
        command.add("-p");
        command.add("MemoryMax=64M");
        command.add("-p");
        command.add("MemorySwapMax=0");
        command.add("-p");
        command.add("TasksMax=16");
        command.add("--");
        command.add("/bin/sh");
        command.add("-c");
        command.add("cat /sys/fs/cgroup$(cut -d: -f3 /proc/self/cgroup)/memory.max");

        Process process = null;
        try {
            // The DAEMON's own environment on purpose: the bus address is what is being
            // tested for, and this child is ours, not a site's.
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "the probe scope did not finish within " + PROBE_TIMEOUT_SECONDS + "s";
            }
            if (process.exitValue() != 0) {
                return "exit " + process.exitValue()
                    + (output.isEmpty() ? "" : ": " + firstLine(output));
            }
            // NEVER trust the exit code alone: systemd-run exits 0 for a scope whose
            // properties the manager silently ignored.
            long expected = 64L * 1024 * 1024;
            if (!String.valueOf(expected).equals(firstLine(output))) {
                return "the scope was created but its memory.max read back as '"
                    + firstLine(output) + "' instead of " + expected;
            }
            return null;
        } catch (Exception failure) {
            if (process != null) {
                process.destroyForcibly();
            }
            return failure.getMessage() != null
                ? failure.getMessage() : failure.getClass().getSimpleName();
        }
    }

    private static @NonNull String firstLine(@NonNull String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** Transient unit names must be unique on the manager and may only carry safe chars. */
    private static @NonNull String unitName(@NonNull String label) {
        StringBuilder safe = new StringBuilder("hohenheim-");
        for (int index = 0; index < label.length() && safe.length() < 40; index++) {
            char character = label.charAt(index);
            safe.append(Character.isLetterOrDigit(character) ? character : '-');
        }
        return safe + "-" + ProcessHandle.current().pid()
            + "-" + SCOPE_SEQUENCE.incrementAndGet();
    }
}
