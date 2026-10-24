package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Predicate;

/**
 * THE one way the controller asks whether a workload's published port answers: from the
 * machine the port is published ON.
 *
 * AIDEV-NOTE: a publication binds the WORKLOAD host's loopback, so on a remote host the
 * controller's own 127.0.0.1 is a different machine. The readiness probe connected there
 * and the release probe had a host-aware lane only for HTTP; a remote workload's port
 * readiness therefore either timed out against an empty controller port or, worse, passed
 * against whatever the controller itself happened to listen on. Both probes now take the
 * lane {@link #forServer} picks: a direct socket for the local host, the host's own shell
 * (the pinned ssh lane) for every other one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public interface PublishedPortProbe {

    /** @return whether a TCP connect to the port on the workload host's loopback succeeds */
    boolean accepts(int port);

    /**
     * One HTTP GET of {@code path} on the port.
     *
     * @return the status code, or 0 when nothing answered
     * @throws IOException when the probe could not be sent at all
     */
    int httpStatus(int port, @NonNull String path) throws IOException, InterruptedException;

    /**
     * One request and its answer over TCP with the port on the workload host's loopback, bounded by {@code wait} and
     * {@code maxBytes}: a status probe's round trip.
     *
     * AIDEV-NOTE: the shell lane cannot ask {@code complete} on the far host, so it reads until the port closes, the
     * cap is reached or the wait ends; a service that keeps the connection open after answering (a Minecraft server
     * does) costs that lane the whole wait. The caller's decoder must tolerate trailing bytes.
     *
     * @param complete whether the bytes read so far are the whole answer, which ends the read early
     * @return the bytes the port answered, empty when nothing connected or answered
     */
    byte @NonNull [] exchange(int port, byte @NonNull [] request, @NonNull Predicate<byte[]> complete, int maxBytes,
                              @NonNull Duration wait);

    /**
     * The lane for the host a workload runs on; null and the local host probe directly.
     *
     * AIDEV-NOTE: a server id whose row is gone gets {@link #unreachable()}, never the local host: the controller's
     * own loopback port of that number belongs to another workload, whose answer would pass for this one's.
     */
    static @NonNull PublishedPortProbe forServer(@Nullable Integer serverId) {
        if (serverId == null || serverId == ServerModel.localServerId()) {
            return local();
        }
        Row server = Models.get(ServerModel.class).findById(serverId);
        return server == null ? unreachable() : over(HostShell.forServer(server));
    }

    /** A lane on which nothing answers, for a host the controller cannot reach at all. */
    static @NonNull PublishedPortProbe unreachable() {
        return Unreachable.INSTANCE;
    }

    /** The controller's own loopback. */
    static @NonNull PublishedPortProbe local() {
        return new Local();
    }

    /** The loopback of the machine {@code shell} runs on. */
    static @NonNull PublishedPortProbe over(@NonNull HostShell shell) {
        return new Shell(shell);
    }

    /** The target URL of an HTTP probe; a path without its leading slash gets one. */
    static @NonNull URI targetOf(int port, @NonNull String path) {
        return URI.create("http://127.0.0.1:" + port + (path.startsWith("/") ? path : "/" + path));
    }

    /** No host to ask: nothing connects, nothing answers. */
    final class Unreachable implements PublishedPortProbe {

        static final Unreachable INSTANCE = new Unreachable();

        private Unreachable() {
        }

        @Override
        public boolean accepts(int port) {
            return false;
        }

        @Override
        public int httpStatus(int port, @NonNull String path) {
            return 0;
        }

        @Override
        public byte @NonNull [] exchange(int port, byte @NonNull [] request, @NonNull Predicate<byte[]> complete,
                                         int maxBytes, @NonNull Duration wait) {
            return new byte[0];
        }
    }

    /** Direct socket and HTTP client on this machine. */
    final class Local implements PublishedPortProbe {

        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        @Override
        public boolean accepts(int port) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                return true;
            } catch (IOException refused) {
                return false;
            }
        }

        @Override
        public int httpStatus(int port, @NonNull String path)
                throws IOException, InterruptedException {
            HttpResponse<Void> response = this.client.send(
                HttpRequest.newBuilder(targetOf(port, path))
                    .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        }

        @Override
        public byte @NonNull [] exchange(int port, byte @NonNull [] request, @NonNull Predicate<byte[]> complete,
                                         int maxBytes, @NonNull Duration wait) {
            Now.Deadline deadline = Now.deadline(wait);
            ByteArrayOutputStream answer = new ByteArrayOutputStream();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutOf(deadline));
                OutputStream out = socket.getOutputStream();
                out.write(request);
                out.flush();
                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[8192];
                while (answer.size() < maxBytes && !deadline.passed() && !complete.test(answer.toByteArray())) {
                    socket.setSoTimeout(timeoutOf(deadline));
                    int read = in.read(buffer, 0, Math.min(buffer.length, maxBytes - answer.size()));
                    if (read < 0) {
                        break;
                    }
                    answer.write(buffer, 0, read);
                }
            } catch (IOException unanswered) {
                // Refused, reset or out of time: what arrived before it is the answer, and nothing is an answer too.
            }
            return answer.toByteArray();
        }

        /** @return the milliseconds left before the deadline, at least one so a socket never waits forever */
        private static int timeoutOf(Now.@NonNull Deadline deadline) {
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, deadline.remainingMillis()));
        }
    }

    /**
     * The workload host's shell: bash's {@code /dev/tcp} for the connect and curl for the
     * HTTP round-trip, both aimed at THAT host's loopback.
     */
    final class Shell implements PublishedPortProbe {

        /** The block dd reads an exchange's answer in. */
        private static final int CHUNK = 4096;

        private final HostShell shell;

        Shell(@NonNull HostShell shell) {
            this.shell = shell;
        }

        @Override
        public boolean accepts(int port) {
            try {
                return this.shell.run("timeout 3 bash -c "
                    + HostShell.quote("exec 3<>/dev/tcp/127.0.0.1/" + port), 10).ok();
            } catch (RuntimeException unanswerable) {
                // An unaddressable host answers nothing, which is "not open", never a crash.
                return false;
            }
        }

        @Override
        public int httpStatus(int port, @NonNull String path) {
            HostShell.Result result;
            try {
                result = this.shell.run(
                    "curl --silent --output /dev/null --write-out '%{http_code}'"
                        + " --connect-timeout 2 --max-time 5 -- "
                        + HostShell.quote(targetOf(port, path).toString()), 10);
            } catch (RuntimeException unanswerable) {
                return 0;
            }
            String code = result.text().trim();
            return result.ok() && code.matches("[0-9]{3}") ? Integer.parseInt(code) : 0;
        }

        @Override
        public byte @NonNull [] exchange(int port, byte @NonNull [] request, @NonNull Predicate<byte[]> complete,
                                         int maxBytes, @NonNull Duration wait) {
            long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
            // bash's /dev/tcp opens the connection, base64 carries the request's bytes in and the answer's out, and
            // timeout bounds the read of a port that keeps the connection open after answering.
            // AIDEV-NOTE: dd, never head: head buffers its output and the timeout's kill discards what it held, so a
            // port that answers and stays open read as no answer at all. dd writes each read through at once.
            String script = "exec 3<>/dev/tcp/127.0.0.1/" + port + " || exit 1; printf %s "
                + HostShell.quote(Base64.getEncoder().encodeToString(request)) + " | base64 -d >&3; timeout "
                + seconds + " dd bs=" + CHUNK + " count=" + ((maxBytes + CHUNK - 1) / CHUNK)
                + " status=none <&3 2>/dev/null | base64 -w0";
            HostShell.Result result;
            try {
                result = this.shell.run("bash -c " + HostShell.quote(script), seconds + 10);
            } catch (RuntimeException unanswerable) {
                return new byte[0];
            }
            if (!result.ok()) {
                return new byte[0];
            }
            try {
                return Base64.getDecoder().decode(result.text().trim());
            } catch (IllegalArgumentException garbled) {
                return new byte[0];
            }
        }
    }
}
