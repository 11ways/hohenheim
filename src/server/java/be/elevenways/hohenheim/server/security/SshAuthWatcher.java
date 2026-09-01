package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * SSH brute-force detection: tails the local sshd journal and feeds every recognized
 * authentication failure to the SAME {@link ThreatScorer} that scores hostname scanning,
 * so crossing the threshold produces an ordinary auto-ban row through the ordinary funnel
 * (never_ban, the own-address guard and the auto-ban budget all apply because they live at
 * that funnel). This is what replaces fail2ban.
 *
 * AIDEV-NOTE: the CHILD-PROCESS LIFECYCLE is the whole risk here, so it is stated once.
 * We spawn {@code journalctl -f -n 0 -o cat -t sshd -t sshd-session -t sshd-auth} and read its stdout line by line on
 * one platform thread. {@code -n 0} means the follow starts at NOW: replaying the journal's
 * backlog at every boot would re-score attacks that are hours old and mass-ban on a
 * restart loop. The child is a long-lived pipe, so three failure modes are handled
 * explicitly and none of them may become a crash loop: (1) journalctl cannot be executed
 * or the journal is unreadable -- the service user is not in the systemd-journal group --
 * which shows up as an IMMEDIATE non-zero exit, is reported ONCE per distinct message and
 * lands in {@link #snapshot()} for the dashboard; (2) the child dies later (log rotation,
 * systemd restart), which is retried with 1s doubling to 60s, the delay resetting after a
 * run that stayed up a full minute; (3) shutdown, where {@link #stop()} destroys the child
 * so the JVM never leaves an orphan journalctl behind. Everything the watcher can do is
 * OBSERVATION: it never touches nftables or the bans table itself.
 *
 * @author Jelle De Loecker
 * @since  0.3.0
 */
public final class SshAuthWatcher {

    /** THE process-wide watcher; boot decides whether it ever starts. */
    public static final SshAuthWatcher INSTANCE = new SshAuthWatcher();

    /**
     * The syslog identifiers sshd logs under. OpenSSH 9.8 split the daemon into
     * {@code sshd} (the listener), {@code sshd-session} (one per connection, where every
     * authentication failure is logged since then) and {@code sshd-auth} (10.0+). Tailing
     * only {@code sshd} on such a host watches the listener's dozen lines a day and bans
     * nobody, which is exactly what happened on Debian 13 (see SshAuthWatchTest).
     */
    static final List<String> IDENTIFIERS = List.of("sshd", "sshd-session", "sshd-auth");

    /** The follow starts at NOW: a backlog replay would mass-ban on every restart. */
    static final List<String> COMMAND = buildCommand();

    private static @NonNull List<String> buildCommand() {
        List<String> command = new java.util.ArrayList<>(List.of("journalctl", "-f", "-n", "0", "-o", "cat"));
        for (String identifier : IDENTIFIERS) {
            command.add("-t");
            command.add(identifier);
        }
        return List.copyOf(command);
    }

    private static final long BACKOFF_MIN_MS = 1_000;
    private static final long BACKOFF_MAX_MS = 60_000;

    /** A run that lasted this long is a healthy one; the next failure restarts the ladder. */
    private static final long HEALTHY_RUN_MS = 60_000;

    /** How the watcher starts its child; injectable so tests never spawn journalctl. */
    public interface Journal {
        @NonNull Process start() throws IOException;
    }

    private final Journal journal;
    private final BiConsumer<String, String> sink;
    private final AtomicLong signals = new AtomicLong();

    private volatile boolean running;
    private volatile @Nullable Thread thread;
    private volatile @Nullable Process child;
    private volatile @Nullable String lastError;
    private volatile @Nullable String reportedError;

    private SshAuthWatcher() {
        this(SshAuthWatcher::spawn,
            (type, ip) -> HohenheimSecurity.scorer().recordEvent(ip, type, 1));
    }

    /** Test constructor: inject the child and the scoring sink. */
    SshAuthWatcher(@NonNull Journal journal, @NonNull BiConsumer<String, String> sink) {
        this.journal = journal;
        this.sink = sink;
    }

    /** Whether an operator asked for SSH watching at all. */
    public static boolean isConfigured() {
        return Boolean.TRUE.equals(
            HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.SSH_WATCH_ENABLED));
    }

    /**
     * Start the supervising thread; idempotent, so a repeated boot does not fork a second
     * journalctl.
     */
    public synchronized void start() {
        if (this.running) {
            return;
        }
        this.running = true;
        Thread supervisor = Thread.ofPlatform().daemon().name("ssh-auth-watch").unstarted(this::supervise);
        this.thread = supervisor;
        supervisor.start();
        Blast.slog("hohenheim.ssh_watch_started", Map.of("command", String.join(" ", COMMAND)));
    }

    /** Stop supervising and destroy the child; idempotent. */
    public synchronized void stop() {
        this.running = false;
        Process current = this.child;
        if (current != null) {
            current.destroy();
        }
        Thread supervisor = this.thread;
        if (supervisor != null) {
            supervisor.interrupt();
        }
        this.thread = null;
    }

    /** What the firewall-role health surface reports. */
    public record Snapshot(boolean configured, boolean running, @Nullable String lastError,
                           long signals) {}

    public @NonNull Snapshot snapshot() {
        return new Snapshot(isConfigured(), this.running, this.lastError, this.signals.get());
    }

    /** Test seam: the number of recognized lines that reached the scorer. */
    long signalCount() {
        return this.signals.get();
    }

    private void supervise() {
        long backoff = BACKOFF_MIN_MS;
        while (this.running) {
            long startedAt = System.currentTimeMillis();
            boolean clean = runOnce();
            if (!this.running) {
                return;
            }
            if (clean && System.currentTimeMillis() - startedAt >= HEALTHY_RUN_MS) {
                backoff = BACKOFF_MIN_MS;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(BACKOFF_MAX_MS, backoff * 2);
        }
    }

    /**
     * One child's whole life: spawn, drain, classify, score.
     *
     * @return true when the child ran and exited on its own terms, false when it could not
     *         run or died reporting an error
     */
    private boolean runOnce() {
        Process process;
        try {
            process = this.journal.start();
        } catch (IOException | RuntimeException e) {
            reportProblem("journalctl could not be started: " + e.getMessage());
            return false;
        }
        this.child = process;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consume(line);
            }
        } catch (IOException e) {
            // A closed pipe during shutdown is normal; anything else is the child dying.
            if (this.running) {
                reportProblem("sshd journal stream ended: " + e.getMessage());
            }
        } finally {
            this.child = null;
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            return false;
        }
        if (!this.running) {
            return true;
        }
        if (exit != 0) {
            reportProblem("journalctl exited " + exit + ": " + readStderr(process));
            return false;
        }
        clearProblem();
        return true;
    }

    /** Score one journal line; unrecognized lines are ignored silently by design. */
    void consume(@Nullable String line) {
        SshAuthLine.Signal signal = SshAuthLine.parse(line);
        if (signal == null) {
            return;
        }
        this.signals.incrementAndGet();
        clearProblem();
        this.sink.accept(signal.eventType(), signal.ip());
    }

    /**
     * Loud ONCE per distinct message: an unreadable journal is a permanent condition that
     * retries every minute, and a line per retry is a log flood nobody reads.
     */
    private void reportProblem(@NonNull String message) {
        this.lastError = message;
        if (message.equals(this.reportedError)) {
            return;
        }
        this.reportedError = message;
        Blast.slog("hohenheim.ssh_watch_unavailable", Map.of("error", message));
        Blast.log("SSH WATCH: not watching sshd -", message,
            "(the service user needs to be in the systemd-journal group)");
    }

    private void clearProblem() {
        this.lastError = null;
        this.reportedError = null;
    }

    private static @NonNull String readStderr(@NonNull Process process) {
        try (InputStream stderr = process.getErrorStream()) {
            String text = new String(stderr.readAllBytes(), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? "no error output" : text;
        } catch (IOException e) {
            return "no error output";
        }
    }

    private static @NonNull Process spawn() throws IOException {
        return new ProcessBuilder(COMMAND).redirectErrorStream(false).start();
    }
}
