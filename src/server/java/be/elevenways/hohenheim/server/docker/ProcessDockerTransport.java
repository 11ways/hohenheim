package be.elevenways.hohenheim.server.docker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link DockerTransport} that runs an external command whose stdio bridges to a Docker daemon
 * -- the model behind {@code docker system dial-stdio} (local) and {@code ssh <host> docker system
 * dial-stdio} (remote). The request is written to the command's stdin and the response read from
 * its stdout to EOF.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class ProcessDockerTransport implements DockerTransport {

    private static final ScheduledExecutorService WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "docker-process-watchdog");
            thread.setDaemon(true);
            return thread;
        });

    /** How long to wait for the stderr drain to finish once stdout has hit EOF. */
    private static final long STDERR_DRAIN_GRACE_MS = 2000;

    private final List<String> command;

    public ProcessDockerTransport(List<String> command) {
        this.command = List.copyOf(command);
    }

    /**
     * The remote command this transport bridges to; the ssh argv around it -- pinned
     * known_hosts, per-host identity -- is built by {@code HostKeys.sshArgv}.
     *
     * AIDEV-NOTE: there is deliberately NO {@code overSsh(String target)} convenience
     * here any more. It spelled {@code StrictHostKeyChecking=accept-new} against the OS
     * user's ambient known_hosts, i.e. silent trust-on-first-use with no pin an operator
     * could ever see; keeping it as a reachable API is how that would come back.
     */
    public static final List<String> DIAL_STDIO = List.of("docker", "system", "dial-stdio");

    @Override
    public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
        return roundTrip(request, timeoutMs, Long.MAX_VALUE);
    }

    @Override
    public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes) throws IOException {
        Process process = new ProcessBuilder(command).start();   // stdout + stderr kept separate

        // Drain stderr so it can't block the process, and keep it for diagnostics on failure.
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try {
                process.getErrorStream().transferTo(stderr);
            } catch (IOException ignored) {
                // process gone
            }
        });
        drain.setDaemon(true);
        drain.start();

        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            process.destroyForcibly();
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write(request);
            stdin.flush();
            // Keep stdin OPEN while reading: dial-stdio tears down the connection on stdin EOF,
            // which truncates the response. The daemon closes after the response (Connection:
            // close), giving us stdout EOF here.
            byte[] response = readBounded(process.getInputStream(), maxResponseBytes);
            if (response.length == 0) {
                // AIDEV-NOTE: JOIN the drain thread before reading its buffer. Reading it
                // straight after stdout EOF races the drain, so the diagnostic text --
                // the ONLY evidence HostProbe classifies on -- came back empty at random
                // and a host-key-verification failure was reported as plain "unreachable".
                try {
                    drain.join(STDERR_DRAIN_GRACE_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                // AIDEV-NOTE: the ARGV must not appear here. HostProbe classifies this
                // message, and an argv carrying "ConnectTimeout=10" made every remote
                // failure -- auth refused, host key changed, docker missing -- classify
                // as "timeout", because the word was in the command we printed rather
                // than in any evidence the host gave us. Only the program name and the
                // real stderr belong in a string something else reads for meaning.
                throw new IOException("Docker transport produced no response ("
                    + command.get(0) + "): "
                    + new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim());
            }
            return response;
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new IOException("Docker transport timed out after " + timeoutMs + "ms");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            process.destroyForcibly();
        }
    }

    /** Read stdout to EOF, aborting DURING the read once it exceeds the cap. */
    private static byte[] readBounded(java.io.InputStream in, long maxResponseBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            if (out.size() > maxResponseBytes) {
                throw new IOException("Docker response exceeded the configured cap of "
                    + maxResponseBytes + " bytes");
            }
        }
        return out.toByteArray();
    }
}
