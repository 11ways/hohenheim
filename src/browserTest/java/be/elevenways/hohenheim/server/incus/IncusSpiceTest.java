package be.elevenways.hohenheim.server.incus;

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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A VM's SPICE server reached the way production reaches it, through a daemon's VGA console operation and one
 * websocket per SPICE channel, with the daemon faked and its websockets carried to a scripted SPICE server: the
 * console is asked for as VGA and forced, its control websocket links first and holds the operation open, every
 * channel links over its own websocket with the operation's secret, and the session draws and types through them;
 * closing the console closes the control websocket and cancels the operation, so nothing is left running.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class IncusSpiceTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void everySpiceChannelRidesItsOwnWebsocketOnTheForcedVgaConsoleUntilTheConsoleCloses() throws Exception {
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
            FakeVgaConsoleDaemon daemon = new FakeVgaConsoleDaemon(spice.port());
            CountDownLatch drawn = new CountDownLatch(1);

            // 1. The daemon is asked for the instance's VGA console, forced over one left open, and the console's
            //    control websocket links before any channel: the daemon keeps the operation only while it is open.
            IncusSpice.Console console = IncusSpice.open(new IncusClient(daemon), "hohenheim-instance-9");
            assertThat(daemon.requests).as("step 1: one console request").hasSize(1);
            assertThat(daemon.requests.getFirst())
                .as("step 1: a forced VGA console of the instance")
                .contains("POST /1.0/instances/hohenheim-instance-9/console")
                .contains("\"type\":\"vga\"")
                .contains("\"force\":true");
            assertThat(daemon.sockets).as("step 1: the control websocket linked at once")
                .containsExactly("/1.0/operations/op-vga-1/websocket?secret=control-secret-1");

            // 2. The screen draws and a key reaches the VM, each channel over its own websocket with the secret.
            try (Session session = Pepperglass.connect(console.options().build(), new SessionListener() {
                    @Override
                    public void regionChanged(@NonNull DisplaySurface surface, @NonNull Rect region) {
                        drawn.countDown();
                    }
                })) {
                assertThat(drawn.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("step 2: the screen drew").isTrue();
                assertThat(session.awaitInputs(WAIT)).as("step 2: inputs linked").isTrue();
                session.key(KeyCode.KEY_A, true);
                assertThat(keys.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).as("step 2: the key A reached the VM")
                    .isEqualTo(0x1EL);
                assertThat(daemon.sockets.subList(1, daemon.sockets.size()))
                    .as("step 2: main, display and inputs each opened a websocket with the data secret")
                    .containsExactly("/1.0/operations/op-vga-1/websocket?secret=data-secret-1",
                        "/1.0/operations/op-vga-1/websocket?secret=data-secret-1",
                        "/1.0/operations/op-vga-1/websocket?secret=data-secret-1");
                assertThat(daemon.running()).as("step 2: the console runs while the session lives")
                    .containsExactly("/1.0/operations/op-vga-1");
            }

            // 3. Closing the console ends the operation: its control websocket closes, which ends it, and the cancel
            //    that follows finds it ended; a second close does nothing.
            console.close();
            console.close();
            assertThat(daemon.running()).as("step 3: no console operation is left running").isEmpty();
            assertThat(daemon.requests.stream().filter(request -> request.startsWith("DELETE")).toList())
                .as("step 3: one cancel of the operation").containsExactly("DELETE /1.0/operations/op-vga-1 null");
        }
    }

    @Test
    void aConsoleWhoseControlSocketCannotLinkIsCancelledAtOnce() throws Exception {
        // 1. The daemon starts the operation but refuses its control websocket.
        FakeVgaConsoleDaemon daemon = new FakeVgaConsoleDaemon(1).refusingControl();
        IncusClient incus = new IncusClient(daemon);

        // 2. Opening the console fails, and the operation it started is cancelled rather than left to the daemon.
        assertThatThrownBy(() -> IncusSpice.open(incus, "hohenheim-instance-9"))
            .as("step 2: the console refuses").hasMessageContaining("websocket upgrade failed");
        assertThat(daemon.started()).as("step 2: one operation was started").isEqualTo(1);
        assertThat(daemon.running()).as("step 2: and none is left running").isEmpty();
    }
}
