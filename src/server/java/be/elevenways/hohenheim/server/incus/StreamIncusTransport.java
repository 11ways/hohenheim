package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.util.Http11;
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
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The shared half of both Incus transports: one fresh channel per REST exchange
 * ({@code Connection: close}, read to EOF, {@link Http11} framing) and the RFC 6455
 * client handshake for the websocket lane. Subclasses only open the byte channel --
 * a pinned+identified TLS socket, or the local unix socket.
 */
abstract class StreamIncusTransport implements IncusTransport {

    // The UnixSocketDockerTransport watchdog shape: blocking channels have no reliable
    // read timeout, so a scheduled close is what bounds a wedged daemon.
    private static final ScheduledExecutorService WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "incus-transport-watchdog");
            thread.setDaemon(true);
            return thread;
        });

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
        byte[] body = jsonBody != null ? jsonBody.getBytes(StandardCharsets.UTF_8) : null;
        byte[] request = Http11.request(method, pathAndQuery, hostHeader(), body,
            body != null ? "application/json" : null, null);
        Channel channel = open(timeoutMs);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            () -> closeQuietly(channel), timeoutMs, TimeUnit.MILLISECONDS);
        try {
            OutputStream out = channel.out();
            out.write(request);
            out.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            channel.in().transferTo(response);
            return Http11.parse(response.toByteArray(), describe());
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

    @Override
    public Http11.@NonNull Raw exchangeUpload(@NonNull String method,
                                              @NonNull String pathAndQuery,
                                              @NonNull Path bodyFile,
                                              @NonNull String contentType,
                                              @Nullable Map<String, String> extraHeaders,
                                              long timeoutMs) throws IOException {
        long length = Files.size(bodyFile);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(hostHeader()).append("\r\n");
        head.append("Accept: application/json\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                head.append(header.getKey()).append(": ").append(header.getValue())
                    .append("\r\n");
            }
        }
        head.append("Content-Type: ").append(contentType).append("\r\n");
        head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Connection: close\r\n\r\n");

        Channel channel = open(timeoutMs);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            () -> closeQuietly(channel), timeoutMs, TimeUnit.MILLISECONDS);
        try {
            OutputStream out = channel.out();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            Files.copy(bodyFile, out);
            out.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            channel.in().transferTo(response);
            return Http11.parse(response.toByteArray(), describe());
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
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            () -> closeQuietly(channel), timeoutMs, TimeUnit.MILLISECONDS);
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
        String head = "GET " + pathAndQuery + " HTTP/1.1\r\n"
            + "Host: " + hostHeader() + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: " + key + "\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n";

        Channel channel = open(connectTimeoutMs);
        // The watchdog covers connect + handshake ONLY: an established stream lives
        // until a side closes it.
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            () -> closeQuietly(channel), connectTimeoutMs, TimeUnit.MILLISECONDS);
        try {
            OutputStream out = channel.out();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
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
