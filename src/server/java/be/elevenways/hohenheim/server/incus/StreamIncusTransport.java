package be.elevenways.hohenheim.server.incus;

import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Watchdog;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.util.Closeables;
import be.elevenways.zenit.server.net.WebSocketStream;
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
import java.time.Duration;
import java.util.Map;

/**
 * The shared half of both Incus transports: one fresh channel per REST exchange
 * ({@code Connection: close}, a bounded {@link Http11} head-then-body read) and the websocket
 * lane, zenit's {@link WebSocketStream} over the same channel: Incus speaks RFC 6455 over its
 * unix socket too, where no URL (and so no outbound policy) applies. Subclasses only open the
 * byte channel -- a pinned+identified TLS socket, or the local unix socket.
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
    static final long MAX_RESPONSE_BYTES = ByteUnit.MEGABYTE.bytes(32);

    /** One open byte channel to the daemon. */
    protected interface Channel extends Closeable {
        @NonNull InputStream in() throws IOException;
        @NonNull OutputStream out() throws IOException;
    }

    /** Open one fresh channel, bounded by {@code connectTimeoutMs}. */
    protected abstract @NonNull Channel open(long connectTimeoutMs) throws IOException;

    /** The {@code Host} header value for requests on this transport. */
    protected abstract @NonNull String hostHeader();

    /**
     * Arm the deadline that closes {@code channel}, saying so in the log when it fires: a wedged daemon answer then
     * leaves a line naming the request it ended instead of only the caller's exception.
     */
    private Watchdog.@NonNull Deadline deadline(@NonNull Channel channel, @NonNull String request, long timeoutMs) {
        return Watchdog.schedule(() -> {
            Blast.log("INCUS: closing", request, "to", describe(), "at its", timeoutMs + "ms", "deadline");
            Closeables.closeQuietly(channel);
        }, timeoutMs);
    }

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
        Watchdog.Deadline watchdog = deadline(channel, method + " " + pathAndQuery, timeoutMs);
        try {
            OutputStream out = channel.out();
            out.write(request);
            out.flush();
            return readAnswer(channel.in(), MAX_RESPONSE_BYTES);
        } catch (IOException e) {
            if (watchdog.expired()) {
                throw new IOException("Incus request to " + describe() + " timed out after "
                    + timeoutMs + "ms");
            }
            throw e;
        } finally {
            watchdog.cancel();
            Closeables.closeQuietly(channel);
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
        Watchdog.Deadline watchdog = deadline(channel, method + " " + pathAndQuery, timeoutMs);
        try {
            OutputStream out = channel.out();
            out.write(head);
            Files.copy(bodyFile, out);
            out.flush();
            return readAnswer(channel.in(), MAX_RESPONSE_BYTES);
        } catch (IOException e) {
            if (watchdog.expired()) {
                throw new IOException("Incus upload to " + describe() + " timed out after "
                    + timeoutMs + "ms");
            }
            throw e;
        } finally {
            watchdog.cancel();
            Closeables.closeQuietly(channel);
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
        Watchdog.Deadline watchdog = deadline(channel, method + " " + pathAndQuery, timeoutMs);
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
            Http11.copyBody(in, head, body, (int) ByteUnit.MEGABYTE.factor(), describe());
            return new Http11.Raw(head.status(), head.headers(), body.toByteArray());
        } catch (IOException e) {
            if (watchdog.expired()) {
                throw new IOException("Incus download from " + describe()
                    + " timed out after " + timeoutMs + "ms");
            }
            throw e;
        } finally {
            watchdog.cancel();
            Closeables.closeQuietly(channel);
        }
    }

    @Override
    public @NonNull IncusWebSocket openWebSocket(@NonNull String pathAndQuery,
                                                 long connectTimeoutMs) throws IOException {
        Channel channel = open(connectTimeoutMs);
        WebSocketStream stream;
        try {
            // The deadline covers the upgrade ONLY: an established stream lives until a side closes it.
            stream = WebSocketStream.upgrade(channel.in(), channel.out(), channel, hostHeader(), pathAndQuery,
                Duration.ofMillis(connectTimeoutMs), MAX_RESPONSE_BYTES);
        } catch (IOException e) {
            Closeables.closeQuietly(channel);
            throw new IOException("Incus websocket upgrade at " + describe() + " failed: " + e.getMessage(), e);
        }
        return new IncusWebSocket() {
            @Override
            public byte @Nullable [] receive() throws IOException {
                WebSocketStream.Message message = stream.receive();
                return message == null ? null : message.payload();
            }

            @Override
            public void send(byte @NonNull [] data) throws IOException {
                stream.sendBinary(data);
            }

            @Override
            public void close() {
                stream.close();
            }
        };
    }
}
