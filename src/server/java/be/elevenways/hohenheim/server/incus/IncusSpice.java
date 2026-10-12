package be.elevenways.hohenheim.server.incus;

import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.kvm.server.ScreenRefusalReason;
import be.elevenways.pepperglass.session.ChannelStream;
import be.elevenways.pepperglass.session.SessionOptions;
import be.elevenways.protoblast.common.time.Backoff;
import be.elevenways.protoblast.server.io.BulkInputStream;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

/**
 * Reaches a virtual machine's SPICE server through its host's Incus daemon: one VGA console operation, held open by
 * its control websocket ({@link IncusConsoleOperation}), and per SPICE channel one websocket on it, each a fresh
 * connection to the server.
 *
 * AIDEV-NOTE: the console is started WITHOUT force unless the viewer asked to take it over: Incus refuses a console
 * while another console operation of the instance runs (an operator's {@code incus console}, of either type, or one
 * an older Hohenheim leaked), and that refusal reads as {@link ScreenRefusalReason#HELD}, whose viewer may take the
 * console over. A held console is asked for again for {@link #HELD_ATTEMPTS} attempts first: a page moving from the
 * serial console to the screen asks for the screen before its serial console is let go. The ticket is empty because
 * the daemon's proxy is the authority, not SPICE.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class IncusSpice {

    /** The words Incus refuses a console with while another one runs (instance_console.go, without force). */
    static final String HELD_REFUSAL = "Force is required to take it over";

    /** How often a held console is asked for before it reads as held, and how long apart. */
    static final int HELD_ATTEMPTS = 8;
    private static final Backoff HELD_RETRY = Backoff.fixed(Duration.ofMillis(250));

    private IncusSpice() {
    }

    /**
     * Starts the instance's VGA console and links its control socket.
     *
     * @param takeOver whether to end a console someone else holds; without it, a held console is refused
     * @throws DomainRefusal {@link ScreenRefusalReason#HELD} when someone else still holds the console after every
     *                       attempt and this is no take-over
     * @throws IOException   when the daemon refuses the console or its control socket; the operation is cancelled then
     */
    public static @NonNull Console open(@NonNull IncusClient incus, @NonNull String handle, boolean takeOver)
            throws IOException {
        String what = "VGA console operation of '" + handle + "'";
        return new Console(handle, IncusConsoleOperation.link(incus, start(incus, handle, takeOver), what), incus);
    }

    /** @return the started VGA console operation, a held console asked for again until the attempts run out */
    private static @NonNull Map<String, Object> start(@NonNull IncusClient incus, @NonNull String handle,
                                                      boolean takeOver) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return incus.startVgaConsole(handle, takeOver);
            } catch (IncusClient.ApiException refused) {
                if (takeOver || !String.valueOf(refused.getMessage()).contains(HELD_REFUSAL)) {
                    throw refused;
                }
                if (attempt >= HELD_ATTEMPTS) {
                    throw ScreenRefusalReason.HELD.refusal("the VGA console of '" + handle + "' is held elsewhere");
                }
            }
            try {
                Thread.sleep(HELD_RETRY.delayAfter(attempt));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while the console of '" + handle + "' was held", interrupted);
            }
        }
    }

    /** One VGA console of a running VM: how a SPICE session reaches it, until it is closed. */
    public static final class Console implements AutoCloseable {

        private final @NonNull String handle;
        private final @NonNull IncusConsoleOperation operation;
        private final @NonNull IncusClient incus;

        private Console(@NonNull String handle, @NonNull IncusConsoleOperation operation,
                        @NonNull IncusClient incus) {
            this.handle = handle;
            this.operation = operation;
            this.incus = incus;
        }

        /** @return how a SPICE session links each channel: a fresh websocket on this console's operation */
        public SessionOptions.@NonNull Builder options() {
            return SessionOptions.dialing(this.handle, (type, id, timeout) ->
                new WebSocketChannel(this.incus.operationWebSocket(this.operation.data())));
        }

        /** Ends the console: its control socket closes and its operation is cancelled; only the first call acts. */
        @Override
        public void close() {
            this.operation.close();
        }
    }

    /** One SPICE channel over one Incus websocket: its messages read as one byte stream, each write sent as one. */
    private static final class WebSocketChannel implements ChannelStream {

        private final @NonNull IncusWebSocket socket;
        private final @NonNull InputStream input = new BulkInputStream() {

            private byte @Nullable [] message;
            private int offset;

            @Override
            public int read(byte @NonNull [] into, int at, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                while (this.message == null || this.offset >= this.message.length) {
                    this.message = WebSocketChannel.this.socket.receive();
                    this.offset = 0;
                    if (this.message == null) {
                        return -1;
                    }
                }
                int taken = Math.min(length, this.message.length - this.offset);
                System.arraycopy(this.message, this.offset, into, at, taken);
                this.offset += taken;
                return taken;
            }

            @Override
            public int available() {
                return this.message == null ? 0 : this.message.length - this.offset;
            }
        };
        private final @NonNull OutputStream output = new OutputStream() {

            @Override
            public void write(int value) throws IOException {
                WebSocketChannel.this.socket.send(new byte[] {(byte) value});
            }

            @Override
            public void write(byte @NonNull [] bytes, int at, int length) throws IOException {
                WebSocketChannel.this.socket.send(Arrays.copyOfRange(bytes, at, at + length));
            }
        };

        WebSocketChannel(@NonNull IncusWebSocket socket) {
            this.socket = socket;
        }

        @Override
        public @NonNull InputStream input() {
            return this.input;
        }

        @Override
        public @NonNull OutputStream output() {
            return this.output;
        }

        @Override
        public void close() {
            this.socket.close();
        }
    }
}
