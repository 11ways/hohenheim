package be.elevenways.hohenheim.server.docker;

import be.elevenways.protoblast.common.dry.Dry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal Docker Engine API client speaking HTTP/1.1 directly over the daemon's
 * unix socket -- the foundation for Hohenext's container/app/database layer.
 *
 * Each call opens a fresh connection with {@code Connection: close} (no keep-alive)
 * and reads to EOF, then decodes a chunked body if present. A per-request watchdog
 * closes the channel if the daemon stalls, so an unresponsive daemon can't pin the
 * calling thread forever. Unversioned API paths are used, so the daemon serves them
 * at its current API version.
 *
 * Request bodies are encoded as plain JSON ({@link #toJson}) rather than via DRY,
 * whose {@code stringify} emits DRY's extended (non-JSON) syntax the daemon rejects.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class DockerClient {

    /** Default Docker daemon socket on Linux. */
    public static final String DEFAULT_SOCKET = "/var/run/docker.sock";

    /** Default per-request deadline (connect + full read); generous enough for a
     *  container stop grace period, short enough to catch a truly-hung daemon. */
    public static final long DEFAULT_TIMEOUT_MS = 60_000;

    /** Deadline for long streaming operations (image pull / build) that legitimately
     *  run for minutes; still bounded so a wedged stream eventually fails. */
    public static final long LONG_OP_TIMEOUT_MS = 600_000;

    // AIDEV-NOTE: A blocking SocketChannel over AF_UNIX can't use Socket.setSoTimeout,
    // so we bound each request with a watchdog that closes the channel on expiry; the
    // blocked connect/read then throws ClosedChannelException, surfaced as a timeout.
    private static final ScheduledExecutorService WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "docker-client-watchdog");
            thread.setDaemon(true);
            return thread;
        });

    private final UnixDomainSocketAddress address;
    private final long timeoutMillis;

    public DockerClient() {
        this(DEFAULT_SOCKET);
    }

    public DockerClient(String socketPath) {
        this(socketPath, DEFAULT_TIMEOUT_MS);
    }

    public DockerClient(String socketPath, long timeoutMillis) {
        this.address = UnixDomainSocketAddress.of(socketPath);
        this.timeoutMillis = timeoutMillis;
    }

    // -----------------------------------------------------------------------
    // Daemon
    // -----------------------------------------------------------------------

    /**
     * @return true if the daemon answers {@code /_ping} with "OK"; false on any error
     */
    public boolean ping() {
        try {
            return "OK".equals(get("/_ping").body().trim());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return the daemon's {@code /version} payload (Version, ApiVersion, Os, Arch, ...)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> version() throws IOException {
        return (Map<String, Object>) parseJson(get("/version").body());
    }

    // -----------------------------------------------------------------------
    // Images
    // -----------------------------------------------------------------------

    /**
     * @return one map per local image as returned by {@code /images/json}
     */
    @SuppressWarnings("unchecked")
    public List<Object> listImages() throws IOException {
        return (List<Object>) parseJson(get("/images/json").body());
    }

    /**
     * Pull an image, blocking until the pull completes. The daemon streams progress
     * (and returns HTTP 200 even on failure), so this scans the stream for an error
     * object and throws if present.
     *
     * @param image repository, e.g. {@code "library/alpine"} or {@code "alpine"}
     * @param tag   tag, e.g. {@code "latest"} (defaults to {@code latest} when null/blank)
     */
    public void pullImage(String image, String tag) throws IOException {
        String resolvedTag = (tag == null || tag.isBlank()) ? "latest" : tag;
        String path = "/images/create?fromImage=" + enc(image) + "&tag=" + enc(resolvedTag);
        String body = request("POST", path, null, null, LONG_OP_TIMEOUT_MS).body();
        if (body.contains("\"error\":")) {
            throw new IOException("Docker image pull failed for " + image + ":" + resolvedTag + " -- " + body.trim());
        }
    }

    /**
     * Build an image from a source directory containing a Dockerfile (the basis of
     * Hohenext's build-from-source pipeline). Blocks until the build finishes; the
     * daemon streams progress (HTTP 200 even on failure), so this throws if the stream
     * carries an error object.
     *
     * @param contextDir build context (tarred and sent as the request body)
     * @param tag        image tag to apply, e.g. {@code "myapp:latest"}
     * @param dockerfile path to the Dockerfile within the context (null -> "Dockerfile")
     */
    public void buildImage(Path contextDir, String tag, String dockerfile) throws IOException {
        byte[] context = tarDirectory(contextDir);
        String file = (dockerfile == null || dockerfile.isBlank()) ? "Dockerfile" : dockerfile;
        String path = "/build?t=" + enc(tag) + "&dockerfile=" + enc(file) + "&rm=true";
        String body = request("POST", path, context, "application/x-tar", LONG_OP_TIMEOUT_MS).body();
        if (body.contains("\"error\":")) {
            throw new IOException("Docker image build failed for " + tag + " -- " + body.trim());
        }
    }

    /**
     * Remove an image by name/tag or id. {@code name} may contain {@code /} and {@code :}
     * (kept as path, not URL-encoded, per the Engine API).
     */
    public void removeImage(String name, boolean force) throws IOException {
        request("DELETE", "/images/" + name + (force ? "?force=true" : ""), null, null, timeoutMillis);
    }

    /**
     * Pull {@code image:tag} only if it is not already present locally. {@code image} may
     * embed the tag (e.g. {@code "postgres:17-alpine"}); a colon that is part of a
     * registry host:port (followed by {@code /}) is not treated as a tag separator.
     */
    public void ensureImage(String image, String tag) throws IOException {
        String repo = image;
        String resolvedTag = tag;
        int colon = image.lastIndexOf(':');
        if (colon > 0 && !image.substring(colon + 1).contains("/")) {
            repo = image.substring(0, colon);
            resolvedTag = image.substring(colon + 1);
        }
        if (resolvedTag == null || resolvedTag.isBlank()) {
            resolvedTag = "latest";
        }
        String ref = repo + ":" + resolvedTag;
        for (Object entry : listImages()) {
            Object repoTags = ((Map<?, ?>) entry).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(ref)) {
                return;
            }
        }
        pullImage(repo, resolvedTag);
    }

    // -----------------------------------------------------------------------
    // Volumes
    // -----------------------------------------------------------------------

    /** Remove a named volume; {@code force} removes it even if in use by stopped containers. */
    public void removeVolume(String name, boolean force) throws IOException {
        request("DELETE", "/volumes/" + enc(name) + (force ? "?force=true" : ""), null, null, timeoutMillis);
    }

    // -----------------------------------------------------------------------
    // Containers
    // -----------------------------------------------------------------------

    /**
     * @param includeStopped also list non-running containers (Docker's {@code all=true})
     * @return one map per container as returned by {@code /containers/json}
     */
    @SuppressWarnings("unchecked")
    public List<Object> listContainers(boolean includeStopped) throws IOException {
        String path = "/containers/json" + (includeStopped ? "?all=true" : "");
        return (List<Object>) parseJson(get(path).body());
    }

    /**
     * Create a container from a spec (e.g. {@code Image}, {@code Cmd}, {@code Env},
     * {@code ExposedPorts}, {@code HostConfig}). The image must already be present
     * locally (call {@link #pullImage} first if unsure).
     *
     * @param name optional container name (null/blank for a daemon-assigned name)
     * @return the new container's id
     */
    @SuppressWarnings("unchecked")
    public String createContainer(String name, Map<String, Object> spec) throws IOException {
        String path = "/containers/create" + (name != null && !name.isBlank() ? "?name=" + enc(name) : "");
        Map<String, Object> result = (Map<String, Object>) parseJson(request("POST", path, toJson(spec)).body());
        return (String) result.get("Id");
    }

    /** Start a created container (idempotent: an already-running container is a no-op). */
    public void startContainer(String id) throws IOException {
        request("POST", "/containers/" + id + "/start", null);
    }

    /** Stop a running container with Docker's default grace period. */
    public void stopContainer(String id) throws IOException {
        stopContainer(id, 10);
    }

    /**
     * Stop a running container, giving it {@code graceSeconds} to exit before SIGKILL.
     * Idempotent (the daemon returns 304 if already stopped). Keep {@code graceSeconds}
     * below the client timeout so the request itself doesn't time out waiting on the grace.
     */
    public void stopContainer(String id, int graceSeconds) throws IOException {
        request("POST", "/containers/" + id + "/stop?t=" + graceSeconds, null);
    }

    /** Remove a container; {@code force} kills it first if running. */
    public void removeContainer(String id, boolean force) throws IOException {
        request("DELETE", "/containers/" + id + (force ? "?force=true" : ""), null);
    }

    /**
     * @return the container's full {@code /json} inspection (State, NetworkSettings, ...)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectContainer(String id) throws IOException {
        return (Map<String, Object>) parseJson(get("/containers/" + id + "/json").body());
    }

    /**
     * Fetch a container's logs as a single text snapshot (stdout and stderr interleaved in
     * arrival order, like {@code docker logs}). Demultiplexes Docker's framed stream for
     * non-TTY containers -- all hohenheim-managed containers run without a TTY.
     *
     * @param tail max number of trailing lines, or {@code <= 0} for the full log
     */
    public String containerLogs(String id, boolean stdout, boolean stderr, int tail) throws IOException {
        String path = "/containers/" + id + "/logs?stdout=" + (stdout ? 1 : 0) + "&stderr=" + (stderr ? 1 : 0);
        if (tail > 0) {
            path += "&tail=" + tail;
        }
        return demuxStream(exchange("GET", path, null, null, timeoutMillis).body());
    }

    /**
     * @return the host port Docker published for {@code containerPort}/tcp on the container
     * @throws IOException if the container did not publish that port
     */
    public int publishedPort(String containerId, int containerPort) throws IOException {
        Object networkSettings = inspectContainer(containerId).get("NetworkSettings");
        Object ports = networkSettings instanceof Map<?, ?> ns ? ns.get("Ports") : null;
        Object bindings = ports instanceof Map<?, ?> p ? p.get(containerPort + "/tcp") : null;
        if (bindings instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> binding) {
            Object hostPort = binding.get("HostPort");
            if (hostPort != null && !hostPort.toString().isBlank()) {
                try {
                    return Integer.parseInt(hostPort.toString());
                } catch (NumberFormatException e) {
                    throw new IOException("Bad published port '" + hostPort + "'");
                }
            }
        }
        throw new IOException("Container did not publish port " + containerPort);
    }

    // -----------------------------------------------------------------------
    // HTTP/1.1 over the unix socket
    // -----------------------------------------------------------------------

    private record Response(int status, String body) {}

    private record RawResponse(int status, byte[] body) {}

    private Response get(String path) throws IOException {
        return request("GET", path, null, null, timeoutMillis);
    }

    private Response request(String method, String path, String jsonBody) throws IOException {
        byte[] body = jsonBody == null ? null : jsonBody.getBytes(StandardCharsets.UTF_8);
        return request(method, path, body, jsonBody == null ? null : "application/json", timeoutMillis);
    }

    private Response request(String method, String path, byte[] body, String contentType, long timeoutMs)
            throws IOException {
        RawResponse response = exchange(method, path, body, contentType, timeoutMs);
        return new Response(response.status(), new String(response.body(), StandardCharsets.UTF_8));
    }

    // Performs the HTTP exchange and returns the byte-accurate body, so binary endpoints
    // (multiplexed log/exec streams) aren't corrupted by a UTF-8 decode.
    private RawResponse exchange(String method, String path, byte[] body, String contentType, long timeoutMs)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: docker\r\n");
        head.append("Accept: application/json\r\n");
        if (body != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");

        byte[] raw;
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            () -> closeQuietly(channel), timeoutMs, TimeUnit.MILLISECONDS);
        try {
            channel.connect(address);
            writeFully(channel, ByteBuffer.wrap(head.toString().getBytes(StandardCharsets.ISO_8859_1)));
            if (body != null && body.length > 0) {
                writeFully(channel, ByteBuffer.wrap(body));
            }
            raw = readToEnd(channel);
        } catch (ClosedChannelException e) {
            throw new IOException(method + " " + path + " timed out after " + timeoutMs + "ms");
        } finally {
            watchdog.cancel(false);
            closeQuietly(channel);
        }
        return parseHttpRaw(raw);
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private static byte[] readToEnd(SocketChannel channel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(8192);
        try {
            int n;
            while ((n = channel.read(buf)) != -1) {
                buf.flip();
                out.write(buf.array(), buf.arrayOffset(), n);
                buf.clear();
            }
        } catch (SocketException e) {
            // AIDEV-NOTE: Docker RSTs some Connection: close streams (notably /build)
            // after sending the full response, rather than a clean FIN. Keep what we
            // read and let parseHttp + chunk decoding validate completeness; only a
            // reset with nothing buffered is a real failure. (Watchdog timeouts surface
            // as ClosedChannelException, handled separately by the caller.)
            if (out.size() == 0) {
                throw e;
            }
        }
        return out.toByteArray();
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    // Tar the build context via the system `tar` (handles file modes, symlinks, and
    // nesting robustly); the daemon's /build endpoint wants the context as a tar body.
    private static byte[] tarDirectory(Path dir) throws IOException {
        Process process = new ProcessBuilder("tar", "-C", dir.toString(), "-cf", "-", ".").start();
        byte[] tar;
        try (var stdout = process.getInputStream()) {
            tar = stdout.readAllBytes();
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("tar of build context timed out: " + dir);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar of build context interrupted: " + dir);
        }
        if (process.exitValue() != 0) {
            throw new IOException("tar of build context failed (exit " + process.exitValue() + "): " + dir);
        }
        return tar;
    }

    // AIDEV-NOTE: We decode raw bytes as ISO-8859-1 (1 char == 1 byte) so header
    // splitting and chunk-size offsets stay byte-accurate, then re-decode the
    // assembled body as UTF-8. Don't "simplify" this to a single UTF-8 decode.
    private static RawResponse parseHttpRaw(byte[] raw) throws IOException {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int sep = text.indexOf("\r\n\r\n");
        if (sep < 0) {
            throw new IOException("Malformed HTTP response from Docker daemon");
        }

        String[] headLines = text.substring(0, sep).split("\r\n");
        int status = parseStatus(headLines[0]);

        boolean chunked = false;
        for (int i = 1; i < headLines.length; i++) {
            String line = headLines[i].toLowerCase();
            if (line.startsWith("transfer-encoding:") && line.contains("chunked")) {
                chunked = true;
            }
        }

        String body = text.substring(sep + 4);
        if (chunked) {
            body = dechunk(body);
        }
        // ISO-8859-1 round-trips each char back to its exact source byte, preserving binary
        // bodies (log/exec frames); JSON callers re-decode these bytes as UTF-8 in request().
        byte[] bodyBytes = body.getBytes(StandardCharsets.ISO_8859_1);

        // 2xx is success; 304 ("not modified") is the daemon's idempotent answer for
        // start/stop when the container is already in the requested state.
        if ((status < 200 || status >= 300) && status != 304) {
            throw new IOException("Docker API returned HTTP " + status + ": "
                + new String(bodyBytes, StandardCharsets.UTF_8).trim());
        }
        return new RawResponse(status, bodyBytes);
    }

    private static int parseStatus(String statusLine) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Bad status line from Docker daemon: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Bad status code from Docker daemon: " + statusLine);
        }
    }

    // Decodes HTTP/1.1 chunked transfer encoding. Throws on malformed framing
    // rather than returning a silently-truncated body (matches parseHttp's strictness).
    private static String dechunk(String body) throws IOException {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            int crlf = body.indexOf("\r\n", pos);
            if (crlf < 0) {
                throw new IOException("Malformed chunked body from Docker daemon: missing chunk header");
            }
            String sizeToken = body.substring(pos, crlf).trim();
            int semicolon = sizeToken.indexOf(';');           // strip any chunk extension
            if (semicolon >= 0) {
                sizeToken = sizeToken.substring(0, semicolon);
            }
            int size;
            try {
                size = Integer.parseInt(sizeToken, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Malformed chunked body from Docker daemon: bad chunk size '" + sizeToken + "'");
            }
            if (size == 0) {
                break;                                          // terminating chunk
            }
            int start = crlf + 2;
            int end = start + size;
            if (end > body.length()) {
                throw new IOException("Malformed chunked body from Docker daemon: chunk exceeds available data");
            }
            out.append(body, start, end);
            pos = end + 2;                                       // skip the chunk's trailing CRLF
        }
        return out.toString();
    }

    // AIDEV-NOTE: Docker multiplexes stdout/stderr over one stream for non-TTY containers:
    // each frame is an 8-byte header [streamType, 0,0,0, size(4, big-endian)] then `size`
    // payload bytes (streamType 1=stdout, 2=stderr). TTY containers send a raw unframed
    // stream; if the bytes don't look framed we emit them verbatim. stdout/stderr are
    // concatenated in arrival order, like `docker logs`. (exec reuses this demux.)
    private static String demuxStream(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos + 8 <= data.length) {
            int streamType = data[pos] & 0xFF;
            if (streamType > 2 || data[pos + 1] != 0 || data[pos + 2] != 0 || data[pos + 3] != 0) {
                out.write(data, pos, data.length - pos);   // not framed (TTY) -> raw remainder
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            int size = ((data[pos + 4] & 0xFF) << 24) | ((data[pos + 5] & 0xFF) << 16)
                     | ((data[pos + 6] & 0xFF) << 8) | (data[pos + 7] & 0xFF);
            pos += 8;
            if (size < 0 || pos + size > data.length) {
                out.write(data, pos, data.length - pos);   // truncated frame -> emit remainder
                break;
            }
            out.write(data, pos, size);
            pos += size;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Object parseJson(String json) {
        return new Dry().parse(json);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Minimal JSON writer (Map / List / String / Number / Boolean / null)
    // -----------------------------------------------------------------------

    /** Encode a Map/List/String/Number/Boolean/null tree as plain JSON for request bodies. */
    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(value, sb);
        return sb.toString();
    }

    private static void writeJson(Object value, StringBuilder sb) {
        switch (value) {
            case null -> sb.append("null");
            case String s -> writeJsonString(s, sb);
            case Boolean b -> sb.append(b.toString());
            case Number n -> sb.append(n.toString());
            case Map<?, ?> map -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeJsonString(String.valueOf(entry.getKey()), sb);
                    sb.append(':');
                    writeJson(entry.getValue(), sb);
                }
                sb.append('}');
            }
            case Iterable<?> list -> {
                sb.append('[');
                boolean first = true;
                for (Object element : list) {
                    if (!first) sb.append(',');
                    first = false;
                    writeJson(element, sb);
                }
                sb.append(']');
            }
            default -> writeJsonString(value.toString(), sb);
        }
    }

    private static void writeJsonString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
