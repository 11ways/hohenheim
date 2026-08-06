package be.elevenways.hohenheim.server.incus;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.util.Arrays;
import java.util.Map;

/**
 * A minimal SPICE client that carries KEYBOARD (and best-effort mouse) INPUT to a VM's
 * VGA console, riding the existing {@link IncusWebSocket} transport -- never a second
 * websocket client. It links exactly the two SPICE channels input needs: MAIN (for the
 * session/connection id) and INPUTS. The FRAMEBUFFER is delivered out of band as VGA
 * PNG snapshots ({@link IncusClient#vgaScreenshot}); this class does no display decode,
 * because qemu's SPICE server GLZ-compresses every display image regardless of the
 * client's preference (verified live on daystrom 2026-08-06), so a streaming decoder
 * would require implementing SPICE's image codecs -- out of scope for the rescue lane.
 *
 * AIDEV-NOTE: the wire layout here is not guessed; it is a direct port of a live probe
 * proven end to end against daystrom's Incus qemu VGA console (link + RSA-OAEP ticket +
 * mini-header + INPUTS key scancodes made the guest screen change). See the SpiceConsole
 * live test for the reproduction. Keyboard is proven; mouse position/press is best
 * effort (a text console shows no cursor to prove it moved).
 */
public final class SpiceConsole implements AutoCloseable {

    private static final int LINK_MAGIC = 0x51444552;   // "REDQ" little-endian
    private static final int MAJOR = 2;
    private static final int MINOR = 2;

    // Common capability bits: AUTH_SELECTION(0), AUTH_SPICE(1), MINI_HEADER(3).
    private static final int COMMON_CAPS = (1 << 0) | (1 << 1) | (1 << 3);
    private static final int AUTH_SPICE = 1;

    private static final int CHANNEL_MAIN = 1;
    private static final int CHANNEL_INPUTS = 3;

    // SPICE_MSG_MAIN_INIT (server -> client, MAIN channel).
    private static final int MSG_MAIN_INIT = 103;
    // SPICE_MSGC_MAIN_ATTACH_CHANNELS (client -> server).
    private static final int MSGC_MAIN_ATTACH_CHANNELS = 104;

    // INPUTS client messages.
    private static final int MSGC_INPUTS_KEY_DOWN = 101;
    private static final int MSGC_INPUTS_KEY_UP = 102;
    private static final int MSGC_INPUTS_MOUSE_MOTION = 111;
    private static final int MSGC_INPUTS_MOUSE_POSITION = 112;
    private static final int MSGC_INPUTS_MOUSE_PRESS = 113;
    private static final int MSGC_INPUTS_MOUSE_RELEASE = 114;

    private final @NonNull IncusWebSocket main;
    private final @NonNull IncusWebSocket inputs;
    private final @NonNull Object inputLock = new Object();
    private volatile boolean closed;

    private SpiceConsole(@NonNull IncusWebSocket main, @NonNull IncusWebSocket inputs) {
        this.main = main;
        this.inputs = inputs;
    }

    /**
     * Open a VGA console operation and link its MAIN and INPUTS SPICE channels.
     *
     * @throws IOException when the daemon refuses the console or the SPICE handshake fails
     */
    public static @NonNull SpiceConsole open(@NonNull IncusClient incus, @NonNull String handle)
            throws IOException {
        Map<String, Object> operation = incus.startVgaConsole(handle, true);
        Object id = operation.get("id");
        Object metadata = operation.get("metadata");
        String secret = metadata instanceof Map<?, ?> meta
            && meta.get("fds") instanceof Map<?, ?> fds
            && fds.get("0") instanceof String value ? value : null;
        if (id == null || secret == null) {
            throw new IOException("VGA console operation of '" + handle
                + "' carried no websocket secret");
        }
        String operationPath = "/1.0/operations/" + id;

        IncusWebSocket main = incus.operationWebSocket(operationPath, secret);
        IncusWebSocket inputs = null;
        try {
            SpiceStream mainStream = new SpiceStream(main);
            link(main, mainStream, 0, CHANNEL_MAIN);
            int sessionId = readSessionId(mainStream);
            // ATTACH_CHANNELS keeps the MAIN channel alive; the reply list is unused.
            send(main, MSGC_MAIN_ATTACH_CHANNELS, new byte[0]);

            inputs = incus.operationWebSocket(operationPath, secret);
            SpiceStream inputStream = new SpiceStream(inputs);
            link(inputs, inputStream, sessionId, CHANNEL_INPUTS);
            return new SpiceConsole(main, inputs);
        } catch (IOException e) {
            main.close();
            if (inputs != null) {
                inputs.close();
            }
            throw e;
        }
    }

    /** Press or release one key, named by its {@code KeyboardEvent.code} string. */
    public void key(@NonNull String code, boolean down) throws IOException {
        int scancode = SpiceScancodes.forCode(code);
        if (scancode == 0) {
            return;   // an unmapped key is dropped, never sent as scancode 0
        }
        int value = down ? SpiceScancodes.makeCode(scancode)
                         : SpiceScancodes.breakCode(scancode);
        synchronized (this.inputLock) {
            send(this.inputs, down ? MSGC_INPUTS_KEY_DOWN : MSGC_INPUTS_KEY_UP,
                le32(value));
        }
    }

    /** Best-effort absolute pointer position (guest tablet); surface coordinates. */
    public void mousePosition(int x, int y, int buttonsMask) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeLe32(body, x);
        writeLe32(body, y);
        writeLe32(body, buttonsMask);
        body.write(0);   // display id
        synchronized (this.inputLock) {
            send(this.inputs, MSGC_INPUTS_MOUSE_POSITION, body.toByteArray());
        }
    }

    /** Best-effort mouse button press/release; {@code button} is the DOM button number. */
    public void mouseButton(int button, boolean down, int buttonsMask) throws IOException {
        byte[] body = new byte[] {(byte) spiceButton(button), (byte) buttonsMask};
        synchronized (this.inputLock) {
            send(this.inputs, down ? MSGC_INPUTS_MOUSE_PRESS : MSGC_INPUTS_MOUSE_RELEASE, body);
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.inputs.close();
        this.main.close();
    }

    // -- SPICE handshake ------------------------------------------------------

    /** Link one channel: RedLinkMess, then SPICE-ticket auth over an empty password. */
    private static void link(@NonNull IncusWebSocket ws, @NonNull SpiceStream stream,
                             int connectionId, int channelType) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeLe32(body, connectionId);
        body.write(channelType);
        body.write(0);                       // channel id
        writeLe32(body, 1);                  // num common caps (one packed bitmask word)
        writeLe32(body, 1);                  // num channel caps
        writeLe32(body, 18);                 // caps offset = sizeof RedLinkMess fixed part
        writeLe32(body, COMMON_CAPS);
        writeLe32(body, 0);                  // channel caps: none required

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeLe32(frame, LINK_MAGIC);
        writeLe32(frame, MAJOR);
        writeLe32(frame, MINOR);
        writeLe32(frame, body.size());
        frame.write(body.toByteArray());
        ws.send(frame.toByteArray());

        // Reply: header(magic,major,minor,size) then body(error, pubkey[162], caps...).
        byte[] header = stream.read(16);
        int magic = readLe32(header, 0);
        int size = readLe32(header, 12);
        if (magic != LINK_MAGIC) {
            throw new IOException("SPICE link reply carried a bad magic: " + magic);
        }
        byte[] reply = stream.read(size);
        int error = readLe32(reply, 0);
        if (error != 0) {
            throw new IOException("SPICE link refused with error " + error);
        }
        byte[] pubKey = Arrays.copyOfRange(reply, 4, 4 + 162);

        // AUTH_SELECTION: choose SPICE ticket auth.
        ws.send(le32(AUTH_SPICE));
        // The ticket is the (empty) password as a NUL-terminated C string, RSA-OAEP(SHA-1).
        ws.send(encryptTicket(pubKey, new byte[] {0}));

        int result = readLe32(stream.read(4), 0);
        if (result != 0) {
            throw new IOException("SPICE ticket auth failed with result " + result);
        }
    }

    /** Read MAIN messages until MAIN_INIT, whose first field is the session id. */
    private static int readSessionId(@NonNull SpiceStream stream) throws IOException {
        for (int guard = 0; guard < 32; guard++) {
            byte[] head = stream.read(6);   // mini header: u16 type, u32 size
            int type = (head[0] & 0xFF) | ((head[1] & 0xFF) << 8);
            int size = readLe32(head, 2);
            byte[] payload = size > 0 ? stream.read(size) : new byte[0];
            if (type == MSG_MAIN_INIT) {
                return readLe32(payload, 0);
            }
        }
        throw new IOException("SPICE MAIN channel sent no MAIN_INIT");
    }

    private static void send(@NonNull IncusWebSocket ws, int type, byte @NonNull [] body)
            throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(type & 0xFF);
        frame.write((type >> 8) & 0xFF);
        writeLe32(frame, body.length);
        frame.write(body, 0, body.length);
        ws.send(frame.toByteArray());
    }

    private static byte @NonNull [] encryptTicket(byte @NonNull [] spkiDer, byte @NonNull [] plain)
            throws IOException {
        try {
            PublicKey key = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(spkiDer));
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new OAEPParameterSpec(
                "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
            return cipher.doFinal(plain);
        } catch (Exception e) {
            throw new IOException("SPICE ticket encryption failed", e);
        }
    }

    private static int spiceButton(int domButton) {
        // DOM: 0 left, 1 middle, 2 right. SPICE: 1 left, 2 middle, 3 right.
        return switch (domButton) {
            case 1 -> 2;
            case 2 -> 3;
            default -> 1;
        };
    }

    private static byte @NonNull [] le32(int value) {
        return new byte[] {
            (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    private static void writeLe32(@NonNull ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static int readLe32(byte @NonNull [] data, int offset) {
        return (data[offset] & 0xFF)
            | ((data[offset + 1] & 0xFF) << 8)
            | ((data[offset + 2] & 0xFF) << 16)
            | ((data[offset + 3] & 0xFF) << 24);
    }

    /** Serves exact byte counts across the transport's message-boundaried frames. */
    private static final class SpiceStream {

        private final @NonNull IncusWebSocket ws;
        private byte @Nullable [] buffer;
        private int offset;

        SpiceStream(@NonNull IncusWebSocket ws) {
            this.ws = ws;
        }

        byte @NonNull [] read(int count) throws IOException {
            byte[] out = new byte[count];
            int filled = 0;
            while (filled < count) {
                if (this.buffer == null || this.offset >= this.buffer.length) {
                    byte[] next = this.ws.receive();
                    if (next == null) {
                        throw new EOFException("SPICE stream ended mid-message");
                    }
                    this.buffer = next;
                    this.offset = 0;
                }
                int take = Math.min(count - filled, this.buffer.length - this.offset);
                System.arraycopy(this.buffer, this.offset, out, filled, take);
                this.offset += take;
                filled += take;
            }
            return out;
        }
    }
}
