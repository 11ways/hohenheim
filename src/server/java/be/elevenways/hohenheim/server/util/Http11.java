package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * THE raw HTTP/1.1 framing both daemon clients share: build one {@code Connection:
 * close} request, parse one read-to-EOF response (headers + optional chunked body).
 * Status POLICY (which codes are refusals, what exception they raise) deliberately
 * stays with each client -- Docker's 304-is-success and Incus's error envelope are
 * different contracts over the same wire framing.
 *
 * AIDEV-NOTE: bytes are decoded as ISO-8859-1 (1 char == 1 byte) so header splitting
 * and chunk-size offsets stay byte-accurate, then the assembled body is re-encoded to
 * its exact source bytes. JSON callers re-decode as UTF-8. Don't "simplify" this to a
 * single UTF-8 decode -- binary bodies (log frames, tars) would be corrupted.
 */
public final class Http11 {

    /**
     * A body exceeded its caller-declared byte cap DURING the read. Typed so a caller can
     * translate the refusal (e.g. name the setting that owns the cap) without string-matching.
     */
    public static final class BodyCapExceededException extends IOException {

        public BodyCapExceededException(@NonNull String message) {
            super(message);
        }
    }

    /** One parsed response: status, lower-cased header names, exact body bytes. */
    public record Raw(int status, @NonNull Map<String, String> headers, byte @NonNull [] body) {

        public @Nullable String header(@NonNull String name) {
            return this.headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private Http11() {
    }

    /** Build one complete request with {@code Connection: close} semantics. */
    public static byte @NonNull [] request(@NonNull String method, @NonNull String path,
                                           @NonNull String hostHeader, byte @Nullable [] body,
                                           @Nullable String contentType,
                                           @Nullable Map<String, String> extraHeaders) {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(hostHeader).append("\r\n");
        head.append("Accept: application/json\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
        }
        if (body != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");

        byte[] headBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        if (body == null || body.length == 0) {
            return headBytes;
        }
        byte[] request = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, request, 0, headBytes.length);
        System.arraycopy(body, 0, request, headBytes.length, body.length);
        return request;
    }

    /**
     * Parse one full raw response; {@code peer} names the daemon in failure text.
     *
     * @throws IOException on malformed framing -- never a silently-truncated body
     */
    public static @NonNull Raw parse(byte @NonNull [] raw, @NonNull String peer)
            throws IOException {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int sep = text.indexOf("\r\n\r\n");
        if (sep < 0) {
            throw new IOException("Malformed HTTP response from " + peer);
        }

        String[] headLines = text.substring(0, sep).split("\r\n");
        int status = parseStatus(headLines[0], peer);

        boolean chunked = false;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < headLines.length; i++) {
            String line = headLines[i].toLowerCase(Locale.ROOT);
            if (line.startsWith("transfer-encoding:") && line.contains("chunked")) {
                chunked = true;
            }
            int colon = headLines[i].indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon), headLines[i].substring(colon + 1).trim());
            }
        }

        String body = text.substring(sep + 4);
        if (chunked) {
            body = dechunk(body, peer);
        }
        return new Raw(status, headers, body.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static int parseStatus(String statusLine, String peer) throws IOException {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Bad status line from " + peer + ": " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Bad status code from " + peer + ": " + statusLine);
        }
    }

    // -- streaming half (bodies too large to buffer) --------------------------

    /** One parsed response HEAD: status + headers, body still on the stream. */
    public record Head(int status, @NonNull Map<String, String> headers) {

        public @Nullable String header(@NonNull String name) {
            return this.headers.get(name.toLowerCase(Locale.ROOT));
        }

        public boolean chunked() {
            String encoding = header("transfer-encoding");
            return encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked");
        }
    }

    /**
     * Read EXACTLY the response head (up to CRLFCRLF) off the stream, leaving the body
     * unread -- the streaming counterpart of {@link #parse}.
     */
    public static @NonNull Head readHead(@NonNull InputStream in, @NonNull String peer)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = in.read();
            if (value < 0) {
                throw new IOException("Connection closed inside the response head from " + peer);
            }
            buffer.write(value);
            boolean cr = value == '\r';
            boolean lf = value == '\n';
            matched = switch (matched) {
                case 0 -> cr ? 1 : 0;
                case 1 -> lf ? 2 : cr ? 1 : 0;
                case 2 -> cr ? 3 : 0;
                default -> lf ? 4 : cr ? 1 : 0;
            };
            if (buffer.size() > 64 * 1024) {
                throw new IOException("Oversized response head from " + peer);
            }
        }
        String[] headLines = buffer.toString(StandardCharsets.ISO_8859_1).trim().split("\r\n");
        int status = parseStatus(headLines[0], peer);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < headLines.length; i++) {
            int colon = headLines[i].indexOf(':');
            if (colon > 0) {
                headers.put(headLines[i].substring(0, colon).toLowerCase(Locale.ROOT),
                    headLines[i].substring(colon + 1).trim());
            }
        }
        return new Head(status, headers);
    }

    /**
     * Stream the response body ({@code Content-Length}, chunked, or read-to-EOF under
     * {@code Connection: close}) to {@code out} without buffering it whole.
     *
     * @param maxBytes hard cap; exceeding it is an IOException, never a truncated file
     * @return bytes written
     */
    public static long copyBody(@NonNull InputStream in, @NonNull Head head,
                                @NonNull OutputStream out, long maxBytes,
                                @NonNull String peer) throws IOException {
        if (head.chunked()) {
            return copyChunked(in, out, maxBytes, peer);
        }
        String declared = head.header("content-length");
        long limit = maxBytes;
        boolean toEof = declared == null;
        long remaining = Long.MAX_VALUE;
        if (!toEof) {
            try {
                remaining = Long.parseLong(declared.trim());
            } catch (NumberFormatException bad) {
                throw new IOException("Bad Content-Length from " + peer + ": " + declared);
            }
            if (remaining > limit) {
                throw new BodyCapExceededException("Response body from " + peer + " ("
                    + remaining + " bytes) exceeds the " + limit + "-byte cap");
            }
        }
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        while (remaining > 0) {
            int want = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, want);
            if (read < 0) {
                if (toEof) {
                    return written;
                }
                throw new IOException("Response body from " + peer + " truncated at "
                    + written + " of " + declared + " bytes");
            }
            written += read;
            if (written > limit) {
                throw new BodyCapExceededException("Response body from " + peer
                    + " exceeds the " + limit + "-byte cap");
            }
            out.write(buffer, 0, read);
            if (!toEof) {
                remaining -= read;
            }
        }
        return written;
    }

    private static long copyChunked(InputStream in, OutputStream out, long maxBytes,
                                    String peer) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        while (true) {
            String sizeToken = readLine(in, peer);
            int semicolon = sizeToken.indexOf(';');
            if (semicolon >= 0) {
                sizeToken = sizeToken.substring(0, semicolon);
            }
            long size;
            try {
                size = Long.parseLong(sizeToken.trim(), 16);
            } catch (NumberFormatException bad) {
                throw new IOException("Malformed chunked body from " + peer
                    + ": bad chunk size '" + sizeToken + "'");
            }
            if (size == 0) {
                // Trailers (if any) end at the first empty line.
                while (!readLine(in, peer).isEmpty()) {
                    // discard trailer
                }
                return written;
            }
            long remaining = size;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("Malformed chunked body from " + peer
                        + ": chunk exceeds available data");
                }
                written += read;
                if (written > maxBytes) {
                    throw new BodyCapExceededException("Response body from " + peer
                        + " exceeds the " + maxBytes + "-byte cap");
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            String terminator = readLine(in, peer);
            if (!terminator.isEmpty()) {
                throw new IOException("Malformed chunked body from " + peer
                    + ": chunk not CRLF-terminated");
            }
        }
    }

    /** One CRLF-terminated line off the stream (the terminator is consumed, not returned). */
    private static @NonNull String readLine(InputStream in, String peer) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = in.read();
            if (value < 0) {
                throw new IOException("Connection closed mid-line from " + peer);
            }
            if (value == '\n') {
                String text = line.toString(StandardCharsets.ISO_8859_1);
                return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
            }
            line.write(value);
            if (line.size() > 64 * 1024) {
                throw new IOException("Oversized line from " + peer);
            }
        }
    }

    /** Chunked transfer decoding; throws on malformed framing rather than truncating. */
    private static String dechunk(String body, String peer) throws IOException {
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (true) {
            int crlf = body.indexOf("\r\n", pos);
            if (crlf < 0) {
                throw new IOException("Malformed chunked body from " + peer
                    + ": missing chunk header");
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
                throw new IOException("Malformed chunked body from " + peer
                    + ": bad chunk size '" + sizeToken + "'");
            }
            if (size == 0) {
                break;                                          // terminating chunk
            }
            int start = crlf + 2;
            int end = start + size;
            if (end > body.length()) {
                throw new IOException("Malformed chunked body from " + peer
                    + ": chunk exceeds available data");
            }
            out.append(body, start, end);
            pos = end + 2;                                       // skip the chunk's trailing CRLF
        }
        return out.toString();
    }
}
