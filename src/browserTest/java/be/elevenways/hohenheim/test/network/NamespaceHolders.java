package be.elevenways.hohenheim.test.network;

import be.elevenways.hohenheim.test.Poll;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Starts the long-lived holder processes the namespace fixtures are built from, and waits until each is READY
 * rather than sleeping a guess.
 *
 * AIDEV-NOTE: readiness is read off the kernel, never timed. {@code unshare} and {@code nsenter} set up every
 * namespace (uid maps included) and only THEN exec their command in the same process, so the moment
 * {@code /proc/<pid>/cmdline} names the final program the namespace exists and the pid is safe to nsenter. A
 * listener is ready when its port appears in {@code /proc/<pid>/net/*}, which shows that process's OWN
 * network namespace. The fixed 300/400 ms sleeps this replaced were both too long on an idle machine and too
 * short on a loaded one, where an nsenter into a namespace that did not exist yet failed the fixture setup.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class NamespaceHolders {

    /** How long a holder may take to exec its program or bind its port. */
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);

    /** The TCP state column value for LISTEN in /proc/net/tcp{,6}. */
    private static final String TCP_LISTEN = "0A";

    private NamespaceHolders() {
    }

    /**
     * Start {@code argv} and wait until its process has exec'd {@code program}; a holder that fails is destroyed.
     *
     * @param program the file name of the command the chain finally execs ("sleep", "python3")
     * @throws IOException when the holder dies first or never gets there
     */
    static @NonNull Process start(@NonNull List<String> argv, @NonNull String program) throws IOException {
        Process process = new ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        try {
            Poll.until("namespace holder exec'd " + program + ": " + String.join(" ", argv), READY_TIMEOUT,
                () -> !process.isAlive() || program.equals(execName(process.pid())));
        } catch (AssertionError notReady) {
            process.destroyForcibly();
            throw new IOException(notReady.getMessage(), notReady);
        }
        if (!process.isAlive()) {
            throw new IOException("namespace holder died immediately: " + String.join(" ", argv));
        }
        return process;
    }

    /**
     * Wait until {@code process} LISTENS on TCP {@code port} (v4 or v6) inside its own network namespace.
     *
     * @throws IOException when the listener dies first or never binds
     */
    static void awaitTcpListen(@NonNull Process process, int port) throws IOException {
        awaitSocket(process, port, "TCP listen", List.of("tcp", "tcp6"), TCP_LISTEN);
    }

    /**
     * Wait until {@code process} has bound UDP {@code port} (v4 or v6) inside its own network namespace.
     *
     * @throws IOException when the responder dies first or never binds
     */
    static void awaitUdpBound(@NonNull Process process, int port) throws IOException {
        awaitSocket(process, port, "UDP bind", List.of("udp", "udp6"), null);
    }

    private static void awaitSocket(@NonNull Process process, int port, @NonNull String what,
                                    @NonNull List<String> tables, @Nullable String state) throws IOException {
        try {
            Poll.until(what + " on port " + port + " by pid " + process.pid(), READY_TIMEOUT,
                () -> !process.isAlive() || hasSocket(process.pid(), tables, port, state));
        } catch (AssertionError notReady) {
            throw new IOException(notReady.getMessage(), notReady);
        }
        if (!process.isAlive()) {
            throw new IOException("the " + what + " holder for port " + port + " died before binding it");
        }
    }

    /** The file name of the program {@code pid} runs now, or null when it cannot be read. */
    private static @Nullable String execName(long pid) {
        try {
            byte[] cmdline = Files.readAllBytes(Path.of("/proc", String.valueOf(pid), "cmdline"));
            int end = 0;
            while (end < cmdline.length && cmdline[end] != 0) {
                end++;
            }
            if (end == 0) {
                return null;
            }
            Path argv0 = Path.of(new String(cmdline, 0, end, StandardCharsets.UTF_8)).getFileName();
            return argv0 == null ? null : argv0.toString();
        } catch (IOException | RuntimeException gone) {
            return null;
        }
    }

    /**
     * Whether one of {@code pid}'s socket tables holds a local socket on {@code port}.
     *
     * @param state the required st column (hex), or null for any state
     */
    private static boolean hasSocket(long pid, @NonNull List<String> tables, int port, @Nullable String state) {
        for (String table : tables) {
            List<String> lines;
            try {
                lines = Files.readAllLines(Path.of("/proc", String.valueOf(pid), "net", table));
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                String[] columns = line.trim().split("\\s+");
                if (columns.length < 4) {
                    continue;
                }
                String local = columns[1];
                int colon = local.lastIndexOf(':');
                if (colon < 0) {
                    continue;
                }
                try {
                    if (Integer.parseInt(local.substring(colon + 1), 16) == port
                            && (state == null || state.equals(columns[3]))) {
                        return true;
                    }
                } catch (NumberFormatException malformed) {
                    // not a socket row
                }
            }
        }
        return false;
    }
}
