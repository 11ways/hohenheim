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

    private final List<String> command;

    public ProcessDockerTransport(List<String> command) {
        this.command = List.copyOf(command);
    }

    /** Drive a remote daemon over SSH; requires key-based ssh access and docker on {@code sshTarget}. */
    public static ProcessDockerTransport overSsh(String sshTarget) {
        return new ProcessDockerTransport(List.of("ssh", "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=10",
            sshTarget, "docker", "system", "dial-stdio"));
    }

    @Override
    public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
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
            byte[] response = process.getInputStream().readAllBytes();
            if (response.length == 0) {
                throw new IOException("Docker transport produced no response (command: " + command + "): "
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
}
