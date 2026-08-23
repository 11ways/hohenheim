package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE interactive (TTY) exec of the Docker tier: create an exec with {@code Tty: true},
 * hijack its start into a live bidirectional {@link ContainerStream}, and resize its
 * pseudo-terminal while it runs.
 *
 * AIDEV-NOTE: this is deliberately NOT a method on {@link DockerClient}. That client's
 * {@code exec} is single-shot and buffered (create, start, read to EOF, inspect for the
 * exit code); an interactive shell is a STREAM whose lifetime belongs to a WebSocket, and
 * the two share no useful code beyond the wire framing -- which is why both sides here
 * ride the shared {@link Http11} codec and {@link ContainerStream} rather than a second
 * copy of either. The only thing spelled locally is the streaming request HEAD, because
 * this is the one endpoint that needs {@code Upgrade: tcp} AND a request body.
 *
 * AIDEV-NOTE: the identity is the CALLER's argument and the caller is the workload's own
 * {@code runUser} -- there is no lane here that lets a user be chosen per request. See
 * {@code InstanceShell}, which is the only production caller and which refuses anything
 * but the workload's declared non-root uid.
 */
public final class DockerPtyExec {

    /** Bound on the exec create / start / resize exchanges; the STREAM itself is unbounded. */
    private static final long CONTROL_TIMEOUT_MS = 30_000;

    /** Nothing sane asks for more, and an unbounded value reaches the daemon as-is. */
    private static final int MAX_DIMENSION = 1000;

    /** POSIX: the program exists but could not be executed. */
    private static final int NOT_EXECUTABLE = 126;

    /** POSIX: the program does not exist. */
    private static final int NOT_FOUND = 127;

    private DockerPtyExec() {
    }

    /** One live pseudo-terminal inside a container: bytes both ways, plus resize. */
    public interface Session {

        /** The live byte pipe; {@code writeStdin} carries keystrokes toward the shell. */
        @NonNull ContainerStream stream();

        /**
         * Tell the pseudo-terminal its new size; without it a shell renders at the size
         * it was created with forever.
         *
         * @throws IOException when the daemon refuses (a finished exec included)
         */
        void resize(int cols, int rows) throws IOException;

        /**
         * Whether the program never really started: the exec is already gone with 126
         * (not executable) or 127 (not found).
         *
         * AIDEV-NOTE: MEASURED against Docker 29.7 -- {@code /exec/{id}/start} answers 101
         * and hijacks the connection even for a Cmd that does not exist, and the exec then
         * reports {@code Running: false, ExitCode: 127} within ~50ms. So this is the only
         * honest way to tell "your image has no bash" from "your shell is running", and it
         * costs ONE bounded control exchange rather than a second, unbounded exec.
         *
         * @throws IOException when the daemon cannot be asked
         */
        boolean failedToStart() throws IOException;

        /** Release the stream; idempotent. */
        void close();
    }

    /**
     * Create and start an interactive exec inside a RUNNING container.
     *
     * @param user    the numeric uid the shell runs as, or null for the container's own
     * @param workdir the directory to start in, or null for the image's
     * @throws IOException when the container is not running, the daemon is unreachable, or
     *         the exec could not be created
     */
    public static @NonNull Session open(@NonNull DockerTransport transport,
                                        @NonNull String containerId,
                                        @NonNull List<String> command,
                                        @Nullable String user, @Nullable String workdir,
                                        @NonNull Map<String, String> env,
                                        int cols, int rows) throws IOException {

        if (!(transport instanceof DockerStreamTransport streaming)) {
            throw new IOException("Docker transport " + transport.getClass().getSimpleName()
                + " has no streaming lane; an interactive shell is unavailable on it");
        }

        int safeCols = clamp(cols, 80);
        int safeRows = clamp(rows, 24);

        String execId = create(transport, containerId, command, user, workdir, env,
            safeCols, safeRows);

        // Detach=false + Tty=true: the daemon hijacks the connection and streams the
        // pseudo-terminal RAW (no stdout/stderr frames), which is why the stream is opened
        // with the framing DECLARED rather than sniffed.
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("Detach", false);
        start.put("Tty", true);
        start.put("ConsoleSize", List.of(safeRows, safeCols));
        byte[] body = Json.stringify(start).getBytes(StandardCharsets.UTF_8);
        byte[] request = streamRequest("/exec/" + execId + "/start", body);
        ContainerStream stream = ContainerStream.open(streaming, request,
            CONTROL_TIMEOUT_MS, true, true);

        return new Session() {

            @Override
            public @NonNull ContainerStream stream() {
                return stream;
            }

            @Override
            public void resize(int newCols, int newRows) throws IOException {
                DockerPtyExec.resize(transport, execId, clamp(newCols, 80), clamp(newRows, 24));
            }

            @Override
            public boolean failedToStart() throws IOException {
                Object parsed = new Dry().parse(new String(
                    exchange(transport, "GET", "/exec/" + enc(execId) + "/json", null).body(),
                    StandardCharsets.UTF_8));
                if (!(parsed instanceof Map<?, ?> info)
                        || Boolean.TRUE.equals(info.get("Running"))) {
                    return false;
                }
                return info.get("ExitCode") instanceof Number code
                    && (code.intValue() == NOT_EXECUTABLE || code.intValue() == NOT_FOUND);
            }

            @Override
            public void close() {
                stream.close();
            }
        };
    }

    /** POST /exec: the exec's identity, before anything is started. */
    private static @NonNull String create(@NonNull DockerTransport transport,
                                          @NonNull String containerId,
                                          @NonNull List<String> command,
                                          @Nullable String user, @Nullable String workdir,
                                          @NonNull Map<String, String> env,
                                          int cols, int rows) throws IOException {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("AttachStdin", true);
        spec.put("AttachStdout", true);
        spec.put("AttachStderr", true);
        spec.put("Tty", true);
        spec.put("Cmd", command);
        spec.put("ConsoleSize", List.of(rows, cols));
        if (!env.isEmpty()) {
            List<String> pairs = new ArrayList<>();
            env.forEach((name, value) -> pairs.add(name + "=" + value));
            spec.put("Env", pairs);
        }
        if (user != null && !user.isBlank()) {
            spec.put("User", user);
        }
        if (workdir != null && !workdir.isBlank()) {
            spec.put("WorkingDir", workdir);
        }

        byte[] body = Json.stringify(spec).getBytes(StandardCharsets.UTF_8);
        Http11.Raw response = exchange(transport, "POST",
            "/containers/" + enc(containerId) + "/exec", body);
        Object parsed = new Dry().parse(new String(response.body(), StandardCharsets.UTF_8));
        Object id = parsed instanceof Map<?, ?> map ? map.get("Id") : null;
        if (!(id instanceof String execId) || execId.isBlank()) {
            throw new IOException("Docker accepted the exec create for '" + containerId
                + "' but returned no exec id");
        }
        return execId;
    }

    /** POST /exec/{id}/resize: the pseudo-terminal's new geometry. */
    private static void resize(@NonNull DockerTransport transport, @NonNull String execId,
                               int cols, int rows) throws IOException {
        exchange(transport, "POST",
            "/exec/" + enc(execId) + "/resize?h=" + rows + "&w=" + cols, null);
    }

    /** One bounded control exchange with Docker's own status policy applied. */
    private static Http11.@NonNull Raw exchange(@NonNull DockerTransport transport,
                                                @NonNull String method, @NonNull String path,
                                                byte @Nullable [] body) throws IOException {
        byte[] request = Http11.request(method, path, "docker", body,
            body == null ? null : "application/json", null);
        Http11.Raw parsed = Http11.parse(
            transport.roundTrip(request, CONTROL_TIMEOUT_MS), "Docker daemon");
        if (parsed.status() < 200 || parsed.status() >= 300) {
            throw new DockerClient.ApiException(parsed.status(),
                "Docker API returned HTTP " + parsed.status() + ": "
                    + new String(parsed.body(), StandardCharsets.UTF_8).trim());
        }
        return parsed;
    }

    /**
     * A streaming request head that also carries a body: {@code Upgrade: tcp} so the
     * daemon answers 101 and hands over the raw connection, and no {@code Connection:
     * close} because the stream's lifetime is the consumer's.
     *
     * AIDEV-NOTE: {@link Http11#request} cannot be used here -- it stamps
     * {@code Connection: close}, which makes the daemon hang up as soon as the exec's
     * first output is flushed. {@code DockerClient}'s own stream-request builder cannot be
     * used either: it emits no body, and {@code /exec/{id}/start} needs one.
     */
    private static byte @NonNull [] streamRequest(@NonNull String path, byte @NonNull [] body) {
        String head = "POST " + path + " HTTP/1.1\r\n"
            + "Host: docker\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: Upgrade\r\n"
            + "Upgrade: tcp\r\n\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] request = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, request, 0, headBytes.length);
        System.arraycopy(body, 0, request, headBytes.length, body.length);
        return request;
    }

    /** Keep a client-supplied dimension inside what a terminal can mean. */
    private static int clamp(int value, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(value, MAX_DIMENSION);
    }

    private static @NonNull String enc(@NonNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
