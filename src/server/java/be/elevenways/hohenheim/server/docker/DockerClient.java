package be.elevenways.hohenheim.server.docker;

import be.elevenways.protoblast.common.dry.Dry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
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
        String body = request("POST", path, null).body();
        if (body.contains("\"error\":")) {
            throw new IOException("Docker image pull failed for " + image + ":" + resolvedTag + " -- " + body.trim());
        }
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

    // -----------------------------------------------------------------------
    // HTTP/1.1 over the unix socket
    // -----------------------------------------------------------------------

    private record Response(int status, String body) {}

    private Response get(String path) throws IOException {
        return request("GET", path, null);
    }

    private Response request(String method, String path, String jsonBody) throws IOException {
        byte[] bodyBytes = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);

        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: docker\r\n");
        head.append("Accept: application/json\r\n");
        if (jsonBody != null) {
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");

        byte[] raw;
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            () -> closeQuietly(channel), timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            channel.connect(address);
            writeFully(channel, ByteBuffer.wrap(head.toString().getBytes(StandardCharsets.ISO_8859_1)));
            if (bodyBytes.length > 0) {
                writeFully(channel, ByteBuffer.wrap(bodyBytes));
            }
            raw = readToEnd(channel);
        } catch (ClosedChannelException e) {
            throw new IOException(method + " " + path + " timed out after " + timeoutMillis + "ms");
        } finally {
            watchdog.cancel(false);
            closeQuietly(channel);
        }
        return parseHttp(raw);
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private static byte[] readToEnd(SocketChannel channel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(8192);
        int n;
        while ((n = channel.read(buf)) != -1) {
            buf.flip();
            out.write(buf.array(), buf.arrayOffset(), n);
            buf.clear();
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

    // AIDEV-NOTE: We decode raw bytes as ISO-8859-1 (1 char == 1 byte) so header
    // splitting and chunk-size offsets stay byte-accurate, then re-decode the
    // assembled body as UTF-8. Don't "simplify" this to a single UTF-8 decode.
    private static Response parseHttp(byte[] raw) throws IOException {
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
        body = new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);

        // 2xx is success; 304 ("not modified") is the daemon's idempotent answer for
        // start/stop when the container is already in the requested state.
        if ((status < 200 || status >= 300) && status != 304) {
            throw new IOException("Docker API returned HTTP " + status + ": " + body.trim());
        }
        return new Response(status, body);
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
