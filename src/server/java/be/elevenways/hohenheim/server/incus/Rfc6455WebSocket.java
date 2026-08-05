package be.elevenways.hohenheim.server.incus;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A minimal RFC 6455 CLIENT over an already-handshaken byte stream: fragmented
 * messages are reassembled, pings are answered, a close frame is echoed and surfaces
 * as an orderly end. Client frames are masked as the RFC requires; server frames must
 * arrive unmasked.
 *
 * AIDEV-NOTE: hand-rolled on purpose. java.net.http's WebSocket cannot disable
 * hostname verification per-client (only via a process-wide system property, which
 * would weaken every OTHER java.net.http user in this JVM, ACME included), and it
 * cannot ride a unix socket at all. Pinning the exact peer certificate happens in the
 * transport below this; this class only frames bytes.
 */
final class Rfc6455WebSocket implements IncusWebSocket {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Refuse absurd frames before allocating for them; console lines are tiny. */
    private static final long MAX_PAYLOAD = 32L * 1024 * 1024;

    private final InputStream in;
    private final OutputStream out;
    private final Closeable channel;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object writeLock = new Object();

    Rfc6455WebSocket(@NonNull InputStream in, @NonNull OutputStream out,
                     @NonNull Closeable channel) {
        this.in = in;
        this.out = out;
        this.channel = channel;
    }

    @Override
    public byte @Nullable [] receive() throws IOException {
        ByteArrayOutputStream message = null;
        while (true) {
            int first;
            try {
                first = this.in.read();
            } catch (IOException e) {
                if (this.closed.get()) {
                    return null;   // our own close unblocked the read
                }
                throw e;
            }
            if (first < 0) {
                if (this.closed.get()) {
                    return null;
                }
                throw new EOFException("websocket stream ended without a close frame");
            }
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            int second = requireByte();
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = ((long) requireByte() << 8) | requireByte();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | requireByte();
                }
            }
            if (length < 0 || length > MAX_PAYLOAD) {
                throw new IOException("websocket frame of " + length + " bytes refused");
            }
            if (masked) {
                throw new IOException("server sent a masked websocket frame");
            }
            byte[] payload = readFully((int) length);

            switch (opcode) {
                case 0x8 -> {                       // close
                    trySendFrame(0x8, payload);
                    this.closed.set(true);
                    return null;
                }
                case 0x9 -> trySendFrame(0xA, payload);   // ping -> pong
                case 0xA -> { /* unsolicited pong: ignore */ }
                case 0x0, 0x1, 0x2 -> {             // continuation / text / binary
                    if (opcode != 0x0 && message != null) {
                        throw new IOException("interleaved websocket message start");
                    }
                    if (message == null) {
                        message = new ByteArrayOutputStream();
                    }
                    message.write(payload);
                    if (fin) {
                        return message.toByteArray();
                    }
                }
                default -> throw new IOException("unknown websocket opcode " + opcode);
            }
        }
    }

    @Override
    public void send(byte @NonNull [] data) throws IOException {
        sendFrame(0x2, data);
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            trySendFrame(0x8, new byte[] {0x03, (byte) 0xE8});   // 1000 normal closure
            try {
                this.channel.close();
            } catch (IOException ignored) {
                // the stream is gone either way
            }
        }
    }

    private int requireByte() throws IOException {
        int value = this.in.read();
        if (value < 0) {
            throw new EOFException("websocket stream ended inside a frame header");
        }
        return value;
    }

    private byte @NonNull [] readFully(int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = this.in.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException("websocket stream ended inside a frame payload");
            }
            offset += read;
        }
        return data;
    }

    private void sendFrame(int opcode, byte @NonNull [] payload) throws IOException {
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length < 65536) {
            frame.write(0x80 | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((long) payload.length >> (8 * i)) & 0xFF);
            }
        }
        frame.write(mask, 0, 4);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        synchronized (this.writeLock) {
            this.out.write(frame.toByteArray());
            this.out.flush();
        }
    }

    /** Control-frame writes on a dying socket are best effort by design. */
    private void trySendFrame(int opcode, byte @NonNull [] payload) {
        try {
            sendFrame(opcode, payload);
        } catch (IOException ignored) {
            // the peer is gone; close() or the caller's next read reports it
        }
    }
}
