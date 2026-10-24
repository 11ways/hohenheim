package be.elevenways.hohenheim.test.instance;

import be.elevenways.protoblast.common.thread.JobRunner;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Minecraft Java edition server as far as its server-list ping goes, on a loopback port: it reads the handshake and
 * the status request and answers with a status JSON naming the players it is told are online.
 *
 * AIDEV-NOTE: like a real server it keeps the connection open after answering (waiting for the ping a client may
 * send), so a reader that waits for the peer to close instead of the answer's declared length is caught by its bound.
 * The protocol is written out here on its own, never through the production codec, so the two cannot agree on a bug.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class FakeMinecraftServer implements AutoCloseable {

    private final ServerSocket socket;
    private final AtomicInteger answered = new AtomicInteger();
    private volatile int online;
    private volatile int capacity;
    private volatile byte @Nullable [] raw;

    private FakeMinecraftServer(@NonNull ServerSocket socket, int online, int capacity) {
        this.socket = socket;
        this.online = online;
        this.capacity = capacity;
    }

    /** @return a server answering on a free loopback port */
    public static @NonNull FakeMinecraftServer start(int online, int capacity) throws IOException {
        FakeMinecraftServer server = new FakeMinecraftServer(
            new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")), online, capacity);
        JobRunner.startVirtualThread(server::accept);
        return server;
    }

    public int port() {
        return this.socket.getLocalPort();
    }

    /** Players online from the next answer on. */
    public void players(int online) {
        this.online = online;
    }

    /** Answer with exactly these bytes from the next request on (a hostile or broken server); null answers honestly. */
    public void rawAnswer(byte @Nullable [] bytes) {
        this.raw = bytes;
    }

    /** @return how many status requests it answered */
    public int answered() {
        return this.answered.get();
    }

    /** Stops answering: the port refuses from now on. */
    @Override
    public void close() throws IOException {
        this.socket.close();
    }

    private void accept() {
        while (!this.socket.isClosed()) {
            Socket client;
            try {
                client = this.socket.accept();
            } catch (IOException closed) {
                return;
            }
            JobRunner.startVirtualThread(() -> this.serve(client));
        }
    }

    private void serve(@NonNull Socket client) {
        try (client) {
            client.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(client.getInputStream());
            byte[] handshake = packet(in);
            byte[] request = packet(in);
            // The handshake asks for the status state (its last byte), and the request is the bare status packet.
            if (handshake.length < 2 || handshake[0] != 0x00 || handshake[handshake.length - 1] != 0x01
                    || request.length != 1 || request[0] != 0x00) {
                return;
            }
            byte[] hostile = this.raw;
            OutputStream out = client.getOutputStream();
            if (hostile != null) {
                out.write(hostile);
            } else {
                String json = "{\"version\":{\"name\":\"Paper 1.21.4\",\"protocol\":769},\"players\":{\"max\":"
                    + this.capacity + ",\"online\":" + this.online + ",\"sample\":[]},\"description\":{\"text\":\"fake\"}}";
                out.write(frame(json.getBytes(StandardCharsets.UTF_8)));
            }
            out.flush();
            this.answered.incrementAndGet();
            // Held open for the ping a client may send, as a real server does.
            InputStream rest = client.getInputStream();
            while (rest.read() >= 0) {
                // drained until the client hangs up or the timeout ends it
            }
        } catch (IOException gone) {
            // the client hung up
        }
    }

    /** @return {@code text} framed as a status answer: packet id 0 and the length-prefixed text, length-prefixed */
    public static byte @NonNull [] frame(byte @NonNull [] text) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0x00);
        varInt(body, text.length);
        body.writeBytes(text);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        varInt(framed, body.size());
        framed.writeBytes(body.toByteArray());
        return framed.toByteArray();
    }

    private static byte @NonNull [] packet(@NonNull DataInputStream in) throws IOException {
        byte[] bytes = new byte[readVarInt(in)];
        in.readFully(bytes);
        return bytes;
    }

    private static int readVarInt(@NonNull DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int part = in.readUnsignedByte();
            value |= (part & 0x7F) << shift;
            if ((part & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }

    private static void varInt(@NonNull ByteArrayOutputStream out, int value) {
        int rest = value;
        while ((rest & ~0x7F) != 0) {
            out.write((rest & 0x7F) | 0x80);
            rest >>>= 7;
        }
        out.write(rest);
    }
}
