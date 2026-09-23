package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Watchdog;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;

/**
 * The shared half of both Incus transports: one fresh channel per REST exchange
 * ({@code Connection: close}, a bounded {@link Http11} head-then-body read) and the RFC 6455
 * client handshake for the websocket lane. Subclasses only open the byte channel --
 * a pinned+identified TLS socket, or the local unix socket.
 */
abstract class StreamIncusTransport implements IncusTransport {

    // The UnixSocketDockerTransport watchdog shape (the shared util.Watchdog): blocking
    // channels have no reliable read timeout, so a scheduled close is what bounds a wedged
    // daemon.

    /**
     * Cap on one REST answer held in memory. The REST lane carries envelopes, listings and
     * the recorded output of an exec; payload-sized bodies ride
     * {@link #exchangeDownload}, never this lane.
     */
    static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** One open byte channel to the daemon. */
    protected interface Channel extends Closeable {
        @NonNull InputStream in() throws IOException;
        @NonNull OutputStream out() throws IOException;
    }

    /** Open one fresh channel, bounded by {@code connectTimeoutMs}. */
    protected abstract @NonNull Channel open(long connectTimeoutMs) throws IOException;

    /** The {@code Host} header value for requests on this transport. */
    protected abstract @NonNull String hostHeader();

    @Override
    public Http11.@NonNull Raw exchange(@NonNull String method, @NonNull String pathAndQuery,
                                        @Nullable String jsonBody, long timeoutMs)
            throws IOException {
        return exchange(method, pathAndQuery, jsonBody, Map.of(), timeoutMs);
    }

    /**
     * AIDEV-NOTE: the answer is read head-first and its body COPIED ONCE under
     * {@link #MAX_RESPONSE_BYTES}, never read to EOF into an unbounded buffer and re-parsed
     * as a string (which held the same bytes four or five times over, with no cap at all).
     */
    @Override
    public Http11.@NonNull Raw exchange(@NonNull String method, @NonNull String pathAndQuery,
                                        @Nullable String jsonBody,
                                        @NonNull Map<String, String> headers, long timeoutMs)
            throws IOException {
        byte[] body = jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : null;
        byte[] request = Http11.request(method, pathAndQuery, hostHeader(), body,
            body != null ? "application/json" : null, headers);
        Channel channel = open(timeoutMs);
        ScheduledFuture<?> watchdog = Watchdog.schedule(() -> closeQuietly(channel), timeoutMs);
        try {
            OutputStream out = channel.out();
            out.write(request);
            out.flush();
            return readAnswer(channel.in(), MAX_RESPONSE_BYTES);
        } catch (IOException e) {
            if (watchdog.isDone()) {
                throw new IOException("Incus request to " + describe() + " timed out after "
                    + timeoutMs + "ms");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            closeQuietly(channel);
        }
    }

    /** One bounded answer: head, then the body copied once under the cap. */
    private Http11.@NonNull Raw readAnswer(@NonNull InputStream in, long maxBytes)
            throws IOException {
        Http11.Head head = Http11.readHead(in, describe());
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        Http11.copyBody(in, head, answer, maxBytes, describe());
        return new Http11.Raw(head.status(), head.headers(), answer.toByteArray());
    }

    @Override
    public Http11.@NonNull Raw exchangeUpload(@NonNull String method,
                                              @NonNull String pathAndQuery,
                                              @NonNull Path bodyFile,
                                              @NonNull String contentType,
                                              @Nullable Map<String, String> extraHeaders,
                                              long timeoutMs) throws IOException {
        long length = Files.size(bodyFile);
        byte[] head = Http11.requestHead(method, pathAndQuery, hostHeader(), contentType,
            length, extraHeaders);

        Channel channel = open(timeoutMs);
        ScheduledFuture<?> watchdog = Watchdog.schedule(() -> closeQuietly(channel), timeoutMs);
        try {
            OutputStream out = channel.out();
            out.write(head);
            Files.copy(bodyFile, out);
            out.flush();
            return readAnswer(channel.in(), MAX_RESPONSE_BYTES);
        } catch (IOException e) {
            if (watchdog.isDone()) {
                throw new IOException("Incus upload to " + describe() + " timed out after "
                    + timeoutMs + "ms");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            closeQuietly(channel);
        }
    }

    @Override
    public Http11.@NonNull Raw exchangeDownload(@NonNull String method,
                                                @NonNull String pathAndQuery,
                                                @NonNull Path destination,
                                                long maxBytes, long timeoutMs)
            throws IOException {
        byte[] request = Http11.request(method, pathAndQuery, hostHeader(), null, null, null);
        Channel channel = open(timeoutMs);
        ScheduledFuture<?> watchdog = Watchdog.schedule(() -> closeQuietly(channel), timeoutMs);
        try {
            OutputStream out = channel.out();
            out.write(request);
            out.flush();
            InputStream in = channel.in();
            Http11.Head head = Http11.readHead(in, describe());
            String contentType = head.header("content-type");
            boolean json = contentType != null && contentType.contains("application/json");
            if (head.status() >= 200 && head.status() < 300 && !json) {
                try (OutputStream file = Files.newOutputStream(destination,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    Http11.copyBody(in, head, file, maxBytes, describe());
                }
                return new Http11.Raw(head.status(), head.headers(), new byte[0]);
            }
            // An envelope (error or refusal): small by contract, hand it back inline.
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            Http11.copyBody(in, head, body, 1024 * 1024, describe());
            return new Http11.Raw(head.status(), head.headers(), body.toByteArray());
        } catch (IOException e) {
            if (watchdog.isDone()) {
                throw new IOException("Incus download from " + describe()
                    + " timed out after " + timeoutMs + "ms");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            closeQuietly(channel);
        }
    }

    @Override
    public @NonNull IncusWebSocket openWebSocket(@NonNull String pathAndQuery,
                                                 long connectTimeoutMs) throws IOException {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Upgrade", "websocket");
        headers.put("Connection", "Upgrade");
        headers.put("Sec-WebSocket-Key", key);
        headers.put("Sec-WebSocket-Version", "13");
        byte[] head = Http11.head("GET", pathAndQuery, hostHeader(), headers);

        Channel channel = open(connectTimeoutMs);
        // The watchdog covers connect + handshake ONLY: an established stream lives
        // until a side closes it.
        ScheduledFuture<?> watchdog = Watchdog.schedule(() -> closeQuietly(channel),
            connectTimeoutMs);
        try {
            OutputStream out = channel.out();
            out.write(head);
            out.flush();
            InputStream in = channel.in();
            String response = readHandshakeHead(in);
            verifyHandshake(response, key);
            watchdog.cancel(false);
            return new Rfc6455WebSocket(in, out, channel);
        } catch (IOException e) {
            closeQuietly(channel);
            if (watchdog.isDone()) {
                throw new IOException("Incus websocket handshake with " + describe()
                    + " timed out after " + connectTimeoutMs + "ms");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
        }
    }

    /** Read EXACTLY the response head (up to CRLFCRLF), never a byte of the first frame. */
    private static @NonNull String readHandshakeHead(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = in.read();
            if (value < 0) {
                throw new IOException("connection closed during the websocket handshake: "
                    + head.toString(StandardCharsets.ISO_8859_1).lines().findFirst().orElse(""));
            }
            head.write(value);
            boolean cr = value == '\r';
            boolean lf = value == '\n';
            matched = switch (matched) {
                case 0 -> cr ? 1 : 0;
                case 1 -> lf ? 2 : cr ? 1 : 0;
                case 2 -> cr ? 3 : 0;
                default -> lf ? 4 : cr ? 1 : 0;
            };
            if (head.size() > 64 * 1024) {
                throw new IOException("oversized websocket handshake response");
            }
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private void verifyHandshake(@NonNull String response, @NonNull String key)
            throws IOException {
        String[] lines = response.split("\r\n");
        if (lines.length == 0 || !lines[0].contains(" 101 ")) {
            throw new IOException("Incus refused the websocket upgrade at " + describe()
                + ": " + (lines.length > 0 ? lines[0] : "(empty response)"));
        }
        String expected = acceptFor(key);
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim()
                    .toLowerCase(Locale.ROOT).equals("sec-websocket-accept")) {
                String accept = line.substring(colon + 1).trim();
                if (!expected.equals(accept)) {
                    throw new IOException("websocket accept mismatch from " + describe());
                }
                return;
            }
        }
        throw new IOException("websocket handshake carried no Sec-WebSocket-Accept");
    }

    private static @NonNull String acceptFor(@NonNull String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static void closeQuietly(@NonNull Channel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
