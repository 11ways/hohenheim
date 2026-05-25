package be.elevenways.hohenheim.server.docker;

import be.elevenways.protoblast.common.dry.Dry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Minimal Docker Engine API client speaking HTTP/1.1 directly over the daemon's
 * unix socket -- the foundation for Hohenext's container/app/database layer.
 *
 * Each call opens a fresh connection with {@code Connection: close} (no keep-alive)
 * and reads to EOF, then decodes a chunked body if present. Unversioned API paths
 * are used, so the daemon serves them at its current API version.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class DockerClient {

    /** Default Docker daemon socket on Linux. */
    public static final String DEFAULT_SOCKET = "/var/run/docker.sock";

    private final UnixDomainSocketAddress address;

    public DockerClient() {
        this(DEFAULT_SOCKET);
    }

    public DockerClient(String socketPath) {
        this.address = UnixDomainSocketAddress.of(socketPath);
    }

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

    /**
     * @param includeStopped also list non-running containers (Docker's {@code all=true})
     * @return one map per container as returned by {@code /containers/json}
     */
    @SuppressWarnings("unchecked")
    public List<Object> listContainers(boolean includeStopped) throws IOException {
        String path = "/containers/json" + (includeStopped ? "?all=true" : "");
        return (List<Object>) parseJson(get(path).body());
    }

    // -----------------------------------------------------------------------
    // HTTP/1.1 over the unix socket
    // -----------------------------------------------------------------------

    private record Response(int status, String body) {}

    private Response get(String path) throws IOException {
        String request = "GET " + path + " HTTP/1.1\r\n"
            + "Host: docker\r\n"
            + "Accept: application/json\r\n"
            + "Connection: close\r\n\r\n";

        byte[] raw;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            raw = readToEnd(channel);
        }
        return parseHttp(raw);
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

        if (status < 200 || status >= 300) {
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

    private static String dechunk(String body) {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < body.length()) {
            int crlf = body.indexOf("\r\n", pos);
            if (crlf < 0) {
                break;
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
                break;
            }
            if (size == 0) {
                break;                                          // terminating chunk
            }
            int start = crlf + 2;
            int end = Math.min(start + size, body.length());
            out.append(body, start, end);
            pos = end + 2;                                       // skip the chunk's trailing CRLF
        }
        return out.toString();
    }

    private static Object parseJson(String json) {
        return new Dry().parse(json);
    }
}
