package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.util.Watchdog;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.RunningProcess;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.server.process.Termination;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * A {@link DockerTransport} that runs an external command whose stdio bridges to a Docker daemon
 * -- the model behind {@code docker system dial-stdio} (local) and {@code ssh <host> docker system
 * dial-stdio} (remote). The request is written to the command's stdin and the response read from
 * its stdout to EOF.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class ProcessDockerTransport implements DockerTransport, DockerStreamTransport {

    /** Bytes of stderr tail kept for diagnostics. */
    private static final int STDERR_TAIL_BYTES = 4096;

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
        // stdout and stderr kept separate: stderr is the diagnostic HostProbe classifies.
        RunningProcess process = start(Subprocess.of(command)
            .timeout(Duration.ofMillis(timeoutMs))
            .stderrLimit(STDERR_TAIL_BYTES));
        boolean done = false;
        try {
            OutputStream stdin = process.stdin();
            stdin.write(request);
            stdin.flush();
            // Keep stdin OPEN while reading: dial-stdio tears down the connection on stdin EOF,
            // which truncates the response. The daemon closes after the response (Connection:
            // close), giving us stdout EOF here.
            byte[] response = readBounded(process.stdout(), maxResponseBytes);
            // AIDEV-NOTE: a timeout ENDS stdout, so the read above returns NORMALLY with
            // whatever arrived before the stop. That is a partial response, not a short one:
            // checked here, after the read, on the outcome close() waits for.
            ProcessOutcome outcome = settle(process);
            done = true;
            if (outcome.termination() == Termination.TIMED_OUT) {
                throw new IOException("Docker transport timed out after " + timeoutMs + "ms");
            }
            if (response.length == 0) {
                // AIDEV-NOTE: the ARGV must not appear here. HostProbe classifies this
                // message, and an argv carrying "ConnectTimeout=10" made every remote
                // failure -- auth refused, host key changed, docker missing -- classify
                // as "timeout", because the word was in the command we printed rather
                // than in any evidence the host gave us. Only the program name and the
                // real stderr belong in a string something else reads for meaning.
                throw new IOException("Docker transport produced no response ("
                    + command.get(0) + "): " + outcome.stderr().text().trim());
            }
            return response;
        } catch (IOException e) {
            if (!done && settle(process).termination() == Termination.TIMED_OUT) {
                throw new IOException("Docker transport timed out after " + timeoutMs + "ms");
            }
            throw e;
        } finally {
            if (!done) {
                settle(process);
            }
        }
    }

    @Override
    public DockerStreamConnection openStream(byte[] request, long connectTimeoutMs)
            throws IOException {
        ProcessStreamConnection connection = new ProcessStreamConnection(
            start(Subprocess.of(command).stderrLimit(STDERR_TAIL_BYTES)));
        // Bound only the request write: ssh may take seconds to connect, but once the
        // stream is live its lifetime belongs to the consumer.
        ScheduledFuture<?> watchdog = Watchdog.schedule(connection::close, connectTimeoutMs);
        try {
            connection.write(request);
        } catch (IOException e) {
            connection.close();
            throw new IOException("Docker stream request could not be sent ("
                + command.get(0) + "): " + connection.diagnostics(), e);
        } finally {
            watchdog.cancel(false);
        }
        return connection;
    }

    /** The bridge process: stdin kept open for the caller, stdout streamed, and a stop that kills at once. */
    private static RunningProcess start(Subprocess process) throws IOException {
        try {
            return process.stdinPipe().streamStdout().stopGrace(Duration.ZERO).start();
        } catch (RuntimeException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
    }

    /** Close stdin, stop what still runs and wait for the outcome. */
    private static ProcessOutcome settle(RunningProcess process) {
        process.close();
        return process.await();
    }

    /**
     * A stream over one ssh/dial-stdio subprocess. The subprocess IS the connection:
     * close() stops it at once, and {@link #isReleased()} reports the observed
     * process state so the leak test counts reality, not a flag.
     */
    private static final class ProcessStreamConnection implements DockerStreamConnection {

        private final RunningProcess process;

        ProcessStreamConnection(RunningProcess process) {
            this.process = process;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return this.process.stdout().read(buffer, offset, length);
        }

        @Override
        public void write(byte[] data) throws IOException {
            OutputStream stdin = this.process.stdin();
            stdin.write(data);
            stdin.flush();
        }

        @Override
        public void close() {
            this.process.close();
        }

        @Override
        public boolean isReleased() {
            return !this.process.isAlive() && this.process.completion().isResolved();
        }

        @Override
        public String diagnostics() {
            String tail = this.process.stderrSoFar().text().trim();
            if (this.process.completion().isResolved()) {
                return "exit " + this.process.await().exitCode() + (tail.isEmpty() ? "" : ": " + tail);
            }
            return tail;
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
