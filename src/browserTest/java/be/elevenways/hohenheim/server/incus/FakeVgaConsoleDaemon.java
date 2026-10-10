package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.pepperglass.link.Capabilities;
import be.elevenways.pepperglass.link.LinkMessage;
import be.elevenways.pepperglass.wire.ChannelType;
import be.elevenways.pepperglass.wire.SpiceException;
import be.elevenways.pepperglass.wire.WireReader;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An Incus daemon serving VGA console operations the way Incus does: each POST starts an operation with a data and a
 * control secret, a data websocket reaches a SPICE server's port, and the operation runs until its control websocket
 * closes or it is cancelled, which also closes its data websockets; cancelling an operation that already ended is
 * refused. Every channel's link capabilities are kept, read from the first message it sends. A POST without force
 * while a console operation runs is refused with Incus's own words (instance_console.go); with force it cancels the
 * running one first.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class FakeVgaConsoleDaemon implements IncusTransport {

    /** Every request as {@code METHOD path body}. */
    public final List<String> requests = new CopyOnWriteArrayList<>();

    /** Every websocket path opened, in order. */
    public final List<String> sockets = new CopyOnWriteArrayList<>();

    private final int spicePort;
    private final AtomicInteger operations = new AtomicInteger();
    private final Map<String, Operation> byPath = new LinkedHashMap<>();
    private final List<Linked> links = new CopyOnWriteArrayList<>();
    private boolean refuseControl;

    /** One channel link as its client announced it. */
    public record Linked(@NonNull ChannelType type, @NonNull Capabilities channelCapabilities) {
    }

    /** One console operation: its secrets, its open data websockets and whether it still runs. */
    private static final class Operation {
        final String path;
        final String data;
        final String control;
        final List<Socket> channels = new ArrayList<>();
        boolean controlLinked;
        boolean running = true;

        Operation(String path, String data, String control) {
            this.path = path;
            this.data = data;
            this.control = control;
        }
    }

    /** @param spicePort where every data websocket is carried to; a closed port makes each channel fail */
    public FakeVgaConsoleDaemon(int spicePort) {
        this.spicePort = spicePort;
    }

    /** @return this daemon, refusing every control websocket as a failed upgrade */
    public @NonNull FakeVgaConsoleDaemon refusingControl() {
        this.refuseControl = true;
        return this;
    }

    /** @return the paths of the console operations still running */
    public synchronized @NonNull List<String> running() {
        return this.byPath.values().stream().filter(operation -> operation.running).map(op -> op.path).toList();
    }

    /** @return the paths of every console operation whose control websocket linked */
    public synchronized @NonNull List<String> controlLinked() {
        return this.byPath.values().stream().filter(operation -> operation.controlLinked).map(op -> op.path)
            .toList();
    }

    /** @return how many console operations were started */
    public synchronized int started() {
        return this.byPath.size();
    }

    /** @return every channel link seen, in order */
    public @NonNull List<Linked> links() {
        return List.copyOf(this.links);
    }

    @Override
    public synchronized Http11.@NonNull Raw exchange(@NonNull String method, @NonNull String pathAndQuery,
                                                     @Nullable String jsonBody, long timeoutMs) throws IOException {
        this.requests.add(method + " " + pathAndQuery + " " + jsonBody);
        if ("POST".equals(method) && pathAndQuery.matches("/1\\.0/instances/[^/]+/console")) {
            boolean force = jsonBody != null && jsonBody.matches("(?s).*\"force\"\\s*:\\s*true.*");
            List<Operation> running = this.byPath.values().stream().filter(op -> op.running).toList();
            if (!running.isEmpty() && !force) {
                return envelope(500, Map.of("type", "error", "error_code", 500,
                    "error", "This console is already connected. Force is required to take it over."));
            }
            for (Operation taken : running) {
                this.end(taken);
            }
            int number = this.operations.incrementAndGet();
            Operation operation = new Operation("/1.0/operations/op-vga-" + number, "data-secret-" + number,
                "control-secret-" + number);
            this.byPath.put(operation.path, operation);
            Map<String, Object> metadata = Map.of("id", "op-vga-" + number, "metadata",
                Map.of("fds", Map.of(IncusClient.OperationSocket.DATA, operation.data,
                    IncusClient.OperationSocket.CONTROL, operation.control)));
            return envelope(202, Map.of("type", "async", "operation", operation.path, "metadata", metadata));
        }
        if ("DELETE".equals(method) && this.byPath.containsKey(pathAndQuery)) {
            Operation operation = this.byPath.get(pathAndQuery);
            if (!operation.running) {
                return envelope(400, Map.of("type", "error", "error_code", 400,
                    "error", "Only running operations can be cancelled"));
            }
            this.end(operation);
            return envelope(200, Map.of("type", "sync", "status", "Success", "status_code", 200));
        }
        throw new IOException("not exercised: " + method + " " + pathAndQuery);
    }

    @Override
    public synchronized @NonNull IncusWebSocket openWebSocket(@NonNull String pathAndQuery, long connectTimeoutMs)
            throws IOException {
        this.sockets.add(pathAndQuery);
        int query = pathAndQuery.indexOf("/websocket?secret=");
        Operation operation = query < 0 ? null : this.byPath.get(pathAndQuery.substring(0, query));
        String secret = query < 0 ? "" : pathAndQuery.substring(query + "/websocket?secret=".length());
        if (operation == null || !operation.running) {
            throw new IOException("Incus API error 404: Operation not found");
        }
        if (secret.equals(operation.control)) {
            if (this.refuseControl) {
                throw new IOException("Incus API error 500: websocket upgrade failed");
            }
            operation.controlLinked = true;
            return this.control(operation);
        }
        if (!secret.equals(operation.data)) {
            throw new IOException("Incus API error 403: bad secret");
        }
        Socket socket = new Socket("127.0.0.1", this.spicePort);
        operation.channels.add(socket);
        return this.channel(socket);
    }

    /** The control websocket: it carries nothing, and closing it ends its operation. */
    private @NonNull IncusWebSocket control(@NonNull Operation operation) {
        CountDownLatch closed = new CountDownLatch(1);
        return new IncusWebSocket() {
            @Override
            public byte @Nullable [] receive() throws IOException {
                try {
                    closed.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
                return null;
            }

            @Override
            public void send(byte @NonNull [] data) {
            }

            @Override
            public void close() {
                closed.countDown();
                synchronized (FakeVgaConsoleDaemon.this) {
                    FakeVgaConsoleDaemon.this.end(operation);
                }
            }
        };
    }

    private @NonNull IncusWebSocket channel(@NonNull Socket socket) {
        return new IncusWebSocket() {
            private final byte[] buffer = new byte[65536];
            private boolean linked;

            @Override
            public byte @Nullable [] receive() throws IOException {
                int count = socket.getInputStream().read(this.buffer);
                return count < 0 ? null : Arrays.copyOf(this.buffer, count);
            }

            @Override
            public void send(byte @NonNull [] data) throws IOException {
                if (!this.linked) {
                    this.linked = true;
                    FakeVgaConsoleDaemon.this.recordLink(data);
                }
                socket.getOutputStream().write(data);
            }

            @Override
            public void close() {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // closing either way
                }
            }
        };
    }

    /** Keeps the channel type and channel capabilities of a SPICE link message. */
    private void recordLink(byte @NonNull [] message) {
        try {
            WireReader header = new WireReader(message);
            if (header.u32() != LinkMessage.MAGIC) {
                return;
            }
            header.skip(12);
            WireReader body = new WireReader(message, LinkMessage.HEADER_SIZE,
                message.length - LinkMessage.HEADER_SIZE);
            body.u32();
            ChannelType type = ChannelType.fromWire(body.u8());
            body.u8();
            int common = (int) body.u32();
            int channel = (int) body.u32();
            long capsOffset = body.u32();
            WireReader caps = new WireReader(message, LinkMessage.HEADER_SIZE + (int) capsOffset,
                message.length - LinkMessage.HEADER_SIZE - (int) capsOffset);
            Capabilities.read(caps, common);
            if (type != null) {
                this.links.add(new Linked(type, Capabilities.read(caps, channel)));
            }
        } catch (SpiceException unreadable) {
            throw new IllegalStateException("A channel's first message was no link message", unreadable);
        }
    }

    /** Ends an operation the way the daemon does: it stops running and its data websockets close. */
    private void end(@NonNull Operation operation) {
        operation.running = false;
        for (Socket socket : operation.channels) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing either way
            }
        }
    }

    private static Http11.@NonNull Raw envelope(int status, @NonNull Map<String, Object> body) {
        return new Http11.Raw(status, Map.of(), Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Http11.@NonNull Raw exchangeUpload(@NonNull String method, @NonNull String pathAndQuery,
                                              @NonNull Path bodyFile, @NonNull String contentType,
                                              @Nullable Map<String, String> extraHeaders, long timeoutMs)
            throws IOException {
        throw new IOException("not exercised");
    }

    @Override
    public Http11.@NonNull Raw exchangeDownload(@NonNull String method, @NonNull String pathAndQuery,
                                                @NonNull Path destination, long maxBytes, long timeoutMs)
            throws IOException {
        throw new IOException("not exercised");
    }

    @Override
    public @NonNull String describe() {
        return "fake-vga-console-daemon";
    }
}
