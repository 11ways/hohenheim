package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.protoblast.common.thread.JobRunner;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link FakeIncusTransport} served on a unix socket, so a host row whose {@code incus_url} names the socket is
 * addressed through the PRODUCTION client lane ({@code IncusClients.forServer}) instead of a substituted client.
 *
 * AIDEV-NOTE: one request per connection, answered with {@code Connection: close}: the shape the stream transport
 * speaks. A request naming its upload ({@code X-Incus-Name}) is served by {@link FakeIncusTransport#exchangeUpload},
 * every other by {@link FakeIncusTransport#exchange}; a websocket reaches no route here.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
final class FakeIncusSocket implements AutoCloseable {

    final FakeIncusTransport daemon = new FakeIncusTransport();
    private final Path socket;
    private final ServerSocketChannel server;
    private final JobRunner serving = JobRunner.create("fake-incus-socket");

    private FakeIncusSocket(@NonNull Path socket, @NonNull ServerSocketChannel server) {
        this.socket = socket;
        this.server = server;
    }

    /** Binds a fresh socket in a temp directory and serves it on a thread {@link #close} stops. */
    static @NonNull FakeIncusSocket start() throws IOException {
        Path directory = Files.createTempDirectory("fake-incus");
        Path socket = directory.resolve("incus.sock");
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        FakeIncusSocket fake = new FakeIncusSocket(socket, server);
        fake.serving.startThread(fake::serve);
        return fake;
    }

    /** @return the {@code incus_url} a host row names to reach this daemon */
    @NonNull String url() {
        return "unix://" + this.socket;
    }

    private void serve() {
        while (this.server.isOpen()) {
            try (SocketChannel channel = this.server.accept()) {
                answer(Channels.newInputStream(channel), Channels.newOutputStream(channel));
            } catch (IOException | RuntimeException closedOrUnrouted) {
                if (!this.server.isOpen()) {
                    return;
                }
            }
        }
    }

    private void answer(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        String[] requestLine = line(in).split(" ");
        int length = 0;
        String contentType = "application/octet-stream";
        Map<String, String> incusHeaders = new HashMap<>();
        for (String header = line(in); !header.isEmpty(); header = line(in)) {
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String name = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            switch (name.toLowerCase(Locale.ROOT)) {
                case "content-length" -> length = Integer.parseInt(value);
                case "content-type" -> contentType = value;
                case "x-incus-name" -> incusHeaders.put("X-Incus-Name", value);
                case "x-incus-type" -> incusHeaders.put("X-Incus-Type", value);
                default -> { }
            }
        }
        byte[] bytes = in.readNBytes(length);
        Http11.Raw raw;
        if (incusHeaders.containsKey("X-Incus-Name")) {
            Path upload = Files.createTempFile("fake-incus-upload-", ".bin");
            try {
                Files.write(upload, bytes);
                raw = this.daemon.exchangeUpload(requestLine[0], requestLine[1], upload, contentType, incusHeaders,
                    5_000);
            } finally {
                Files.deleteIfExists(upload);
            }
        } else {
            raw = this.daemon.exchange(requestLine[0], requestLine[1],
                length == 0 ? null : new String(bytes, StandardCharsets.UTF_8), 5_000);
        }
        out.write(("HTTP/1.1 " + raw.status() + " Fake\r\nContent-Type: application/json\r\nContent-Length: "
            + raw.body().length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(raw.body());
        out.flush();
    }

    private static @NonNull String line(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int read = in.read(); read != -1 && read != '\n'; read = in.read()) {
            if (read != '\r') {
                bytes.write(read);
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() throws IOException {
        this.server.close();
        this.serving.shutdownNow();
        Files.deleteIfExists(this.socket);
    }
}
