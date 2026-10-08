package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.pepperglass.Pepperglass;
import be.elevenways.pepperglass.fixture.ScriptedSpiceServer;
import be.elevenways.pepperglass.session.DisplaySurface;
import be.elevenways.pepperglass.session.Session;
import be.elevenways.pepperglass.session.SessionListener;
import be.elevenways.pepperglass.wire.ChannelType;
import be.elevenways.pepperglass.wire.DisplayMessages;
import be.elevenways.pepperglass.wire.InputsMessages;
import be.elevenways.pepperglass.wire.MainMessages;
import be.elevenways.pepperglass.wire.Rect;
import be.elevenways.pepperglass.wire.WireReader;
import be.elevenways.pepperglass.wire.WireWriter;
import be.elevenways.protoblast.common.input.KeyCode;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VM's SPICE server reached the way production reaches it, through a daemon's VGA console operation and one
 * websocket per SPICE channel, with the daemon faked and its websockets carried to a scripted SPICE server: the
 * console is asked for as VGA and forced, every channel links over its own websocket with the operation's secret, and
 * the session draws and types through them.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class IncusSpiceTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void everySpiceChannelRidesItsOwnWebsocketOnTheForcedVgaConsole() throws Exception {
        BlockingQueue<Long> keys = new LinkedBlockingQueue<>();
        try (ScriptedSpiceServer spice = ScriptedSpiceServer.builder()
                .channel(ChannelType.MAIN, channel -> {
                    channel.send(MainMessages.INIT, ScriptedSpiceServer.mainInit(4));
                    channel.expect(MainMessages.CLIENT_ATTACH_CHANNELS, WAIT, new ArrayList<>());
                    channel.send(MainMessages.CHANNELS_LIST, new WireWriter().u32(2)
                        .u8(ChannelType.DISPLAY.wire()).u8(0).u8(ChannelType.INPUTS.wire()).u8(0).toByteArray());
                    channel.drainUntilClosed();
                })
                .channel(ChannelType.DISPLAY, channel -> {
                    channel.expect(DisplayMessages.CLIENT_INIT, WAIT, new ArrayList<>());
                    channel.send(DisplayMessages.SURFACE_CREATE, new WireWriter().u32(0).u32(8).u32(8).u32(32).u32(1)
                        .toByteArray());
                    WireWriter fill = new WireWriter().u32(0);
                    new Rect(0, 0, 8, 8).write(fill);
                    fill.u8(0).u8(1).u32(0x00FF00).u16(1 << 3).u8(0).u32(0).u32(0).u32(0);
                    channel.send(DisplayMessages.DRAW_FILL, fill.toByteArray());
                    channel.drainUntilClosed();
                })
                .channel(ChannelType.INPUTS, channel -> channel.untilClosed(frame -> {
                    if (frame.type() == InputsMessages.CLIENT_KEY_DOWN) {
                        keys.add(new WireReader(frame.data(), frame.offset(), frame.length()).u32());
                    }
                }))
                .start()) {
            Daemon daemon = new Daemon(spice.port());
            CountDownLatch drawn = new CountDownLatch(1);

            // 1. The daemon is asked for the instance's VGA console, forced over one left open.
            try (Session session = Pepperglass.connect(IncusSpice.options(new IncusClient(daemon),
                    "hohenheim-instance-9").build(), new SessionListener() {
                        @Override
                        public void regionChanged(@NonNull DisplaySurface surface, @NonNull Rect region) {
                            drawn.countDown();
                        }
                    })) {
                assertThat(daemon.requests).as("step 1: one console request").hasSize(1);
                assertThat(daemon.requests.getFirst())
                    .as("step 1: a forced VGA console of the instance")
                    .contains("POST /1.0/instances/hohenheim-instance-9/console")
                    .contains("\"type\":\"vga\"")
                    .contains("\"force\":true");

                // 2. The screen draws and a key reaches the VM, each channel over its own websocket with the secret.
                assertThat(drawn.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("step 2: the screen drew").isTrue();
                assertThat(session.awaitInputs(WAIT)).as("step 2: inputs linked").isTrue();
                session.key(KeyCode.KEY_A, true);
                assertThat(keys.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).as("step 2: the key A reached the VM")
                    .isEqualTo(0x1EL);
                assertThat(daemon.sockets).as("step 2: main, display and inputs each opened a websocket")
                    .containsExactly("/1.0/operations/op-vga/websocket?secret=spice-secret",
                        "/1.0/operations/op-vga/websocket?secret=spice-secret",
                        "/1.0/operations/op-vga/websocket?secret=spice-secret");
            }
        }
    }

    /** An Incus daemon answering one VGA console operation whose websockets reach a SPICE server's port. */
    private static final class Daemon implements IncusTransport {

        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> sockets = new CopyOnWriteArrayList<>();
        private final int spicePort;

        Daemon(int spicePort) {
            this.spicePort = spicePort;
        }

        @Override
        public Http11.@NonNull Raw exchange(@NonNull String method, @NonNull String pathAndQuery,
                                            @Nullable String jsonBody, long timeoutMs) throws IOException {
            this.requests.add(method + " " + pathAndQuery + " " + jsonBody);
            if (!"POST".equals(method) || !pathAndQuery.endsWith("/console")) {
                throw new IOException("not exercised: " + method + " " + pathAndQuery);
            }
            Map<String, Object> operation = Map.of("id", "op-vga",
                "metadata", Map.of("fds", Map.of("0", "spice-secret", "control", "control-secret")));
            return new Http11.Raw(202, Map.of(), Json.stringify(Map.of("type", "async",
                "operation", "/1.0/operations/op-vga", "metadata", operation)).getBytes(StandardCharsets.UTF_8));
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
        public @NonNull IncusWebSocket openWebSocket(@NonNull String pathAndQuery, long connectTimeoutMs)
                throws IOException {
            this.sockets.add(pathAndQuery);
            Socket socket = new Socket("127.0.0.1", this.spicePort);
            return new IncusWebSocket() {
                private final byte[] buffer = new byte[4096];

                @Override
                public byte @Nullable [] receive() throws IOException {
                    int count = socket.getInputStream().read(this.buffer);
                    return count < 0 ? null : Arrays.copyOf(this.buffer, count);
                }

                @Override
                public void send(byte @NonNull [] data) throws IOException {
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

        @Override
        public @NonNull String describe() {
            return "faked-incus-daemon";
        }
    }
}
