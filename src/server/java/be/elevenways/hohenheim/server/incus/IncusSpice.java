package be.elevenways.hohenheim.server.incus;

import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.kvm.server.ScreenRefusalReason;
import be.elevenways.pepperglass.session.ChannelStream;
import be.elevenways.pepperglass.session.SessionOptions;
import be.elevenways.protoblast.server.io.BulkInputStream;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reaches a virtual machine's SPICE server through its host's Incus daemon: one VGA console operation, held open by
 * its control websocket, and per SPICE channel one websocket on it, each a fresh connection to the server.
 *
 * AIDEV-NOTE: the console is started WITHOUT force unless the viewer asked to take it over: Incus refuses a console
 * while another console operation of the instance runs (an operator's {@code incus console --type=vga}, or one an
 * older Hohenheim leaked on 6.0-6.16), and that refusal reads as {@link ScreenRefusalReason#HELD}, whose viewer may
 * take the console over. The ticket is empty because the daemon's proxy is the authority, not SPICE. The daemon ends a
 * VGA console operation only when its control websocket closes: Incus 6.0 through 6.16 keep one whose control never
 * connected running forever, and later releases fail it after 10 seconds, closing every SPICE channel with it. So the
 * control socket is linked at once and {@link Console#close} closes it and cancels the operation.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class IncusSpice {

    private static final Logger LOG = Logger.getLogger(IncusSpice.class.getName());

    /** The words Incus refuses a console with while another one runs (instance_console.go, without force). */
    static final String HELD_REFUSAL = "Force is required to take it over";

    private IncusSpice() {
    }

    /**
     * Starts the instance's VGA console and links its control socket.
     *
     * @param takeOver whether to end a console someone else holds; without it, a held console is refused
     * @throws DomainRefusal {@link ScreenRefusalReason#HELD} when someone else holds the console and this is no
     *                       take-over
     * @throws IOException   when the daemon refuses the console or its control socket; the operation is cancelled then
     */
    public static @NonNull Console open(@NonNull IncusClient incus, @NonNull String handle, boolean takeOver)
            throws IOException {
        String what = "VGA console operation of '" + handle + "'";
        Map<String, Object> operation;
        try {
            operation = incus.startVgaConsole(handle, takeOver);
        } catch (IncusClient.ApiException refused) {
            if (!takeOver && String.valueOf(refused.getMessage()).contains(HELD_REFUSAL)) {
                throw ScreenRefusalReason.HELD.refusal("the VGA console of '" + handle + "' is held elsewhere");
            }
            throw refused;
        }
        IncusClient.OperationSocket channels = IncusClient.OperationSocket.of(operation,
            IncusClient.OperationSocket.DATA, what);
        IncusWebSocket control;
        try {
            control = incus.operationWebSocket(IncusClient.OperationSocket.of(operation,
                IncusClient.OperationSocket.CONTROL, what));
        } catch (IOException refused) {
            cancel(incus, channels.operationPath());
            throw refused;
        }
        return new Console(incus, handle, channels, control);
    }

    /** Cancels an operation that may have ended already; a failure is only logged, the operation ends either way. */
    private static void cancel(@NonNull IncusClient incus, @NonNull String operationPath) {
        try {
            incus.cancelOperation(operationPath);
        } catch (IOException ended) {
            LOG.log(Level.FINE, "Incus did not cancel " + operationPath, ended);
        }
    }

    /** One VGA console of a running VM: how a SPICE session reaches it, until it is closed. */
    public static final class Console implements AutoCloseable {

        private final @NonNull IncusClient incus;
        private final @NonNull String handle;
        private final IncusClient.@NonNull OperationSocket channels;
        private final @NonNull IncusWebSocket control;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Console(@NonNull IncusClient incus, @NonNull String handle,
                        IncusClient.@NonNull OperationSocket channels, @NonNull IncusWebSocket control) {
            this.incus = incus;
            this.handle = handle;
            this.channels = channels;
            this.control = control;
        }

        /** @return how a SPICE session links each channel: a fresh websocket on this console's operation */
        public SessionOptions.@NonNull Builder options() {
            return SessionOptions.dialing(this.handle, (type, id, timeout) ->
                new WebSocketChannel(this.incus.operationWebSocket(this.channels)));
        }

        /** Ends the console: its control socket closes and its operation is cancelled; only the first call acts. */
        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.control.close();
                cancel(this.incus, this.channels.operationPath());
            }
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
