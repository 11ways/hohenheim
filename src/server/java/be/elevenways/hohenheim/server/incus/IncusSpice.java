package be.elevenways.hohenheim.server.incus;

import be.elevenways.pepperglass.session.ChannelStream;
import be.elevenways.pepperglass.session.SessionOptions;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Reaches a virtual machine's SPICE server through its host's Incus daemon: one VGA console operation, and per SPICE
 * channel one websocket on it, each a fresh connection to the server.
 *
 * AIDEV-NOTE: the console is started with force, so it takes over a VGA console someone else left open; the ticket is
 * empty because the daemon's proxy is the authority, not SPICE.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class IncusSpice {

    private IncusSpice() {
    }

    /**
     * Starts the instance's VGA console and returns how a SPICE session reaches it.
     *
     * @throws IOException when the daemon refuses the console
     */
    public static SessionOptions.@NonNull Builder options(@NonNull IncusClient incus, @NonNull String handle)
            throws IOException {
        IncusClient.OperationSocket console = IncusClient.OperationSocket.of(incus.startVgaConsole(handle, true),
            "VGA console operation of '" + handle + "'");
        return SessionOptions.dialing(handle, (type, id, timeout) ->
            new WebSocketChannel(incus.operationWebSocket(console)));
    }

    /** One SPICE channel over one Incus websocket: its messages read as one byte stream, each write sent as one. */
    private static final class WebSocketChannel implements ChannelStream {

        private final @NonNull IncusWebSocket socket;
        private final @NonNull InputStream input = new InputStream() {

            private byte @Nullable [] message;
            private int offset;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return this.read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
            }

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
