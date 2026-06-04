package be.elevenways.hohenheim.server.proxy;

import be.elevenways.protoblast.common.Blast;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback TCP-to-AF_UNIX bridge: binds {@code 127.0.0.1:0} and splices each accepted TCP connection
 * to a fresh AF_UNIX connection to the configured socket path. This lets Undertow's TCP-only proxy
 * client reach a unix-socket upstream by dialing the bridge's loopback port.
 *
 * This is the correct transport, not a workaround: Undertow 2.3 over xnio-nio has no AF_UNIX client
 * support (the JNI xnio-native provider is absent), so there is no clean ClientProvider path. Undertow
 * stays fully async on its side; the raw byte-copy splice runs on virtual threads where blocking I/O
 * is cheap. Reuses the AF_UNIX {@code SocketChannel.open(UNIX)} idiom proven by UnixSocketDockerTransport.
 */
public final class UnixSocketBridge {

    private static final int BUFFER_SIZE = 16384;

    // AIDEV-NOTE: a freshly spawned child may not have bound its AF_UNIX socket yet at the first dial
    // (the spawn TOCTOU window), so the upstream connect is retried briefly. These are the only race
    // attempts; once the socket exists the first try succeeds.
    private static final int SPAWN_RACE_RETRIES = 3;
    private static final long SPAWN_RACE_BACKOFF_MS = 100;

    private final UnixDomainSocketAddress upstream;
    private final ServerSocketChannel server;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public UnixSocketBridge(String socketPath) throws IOException {
        this.upstream = UnixDomainSocketAddress.of(socketPath);
        this.server = ServerSocketChannel.open();
        this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        this.port = ((InetSocketAddress) this.server.getLocalAddress()).getPort();
        Thread.ofVirtual().name("unix-bridge-accept-" + port).start(this::acceptLoop);
    }

    /** The loopback TCP port the proxy client dials. */
    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (!closed.get()) {
            SocketChannel tcp;
            try {
                tcp = server.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    Blast.log("UnixSocketBridge accept failed:", e.getMessage());
                }
                return;
            }
            spliceToUpstream(tcp);
        }
    }

    private void spliceToUpstream(SocketChannel tcp) {
        SocketChannel unix;
        try {
            unix = connectUpstream();
        } catch (IOException e) {
            closeQuietly(tcp);
            return;
        }
        // One virtual thread per direction; the first to see EOF/error closes both so the peer's
        // copy loop unblocks.
        Thread.ofVirtual().start(() -> { copy(tcp, unix); closeQuietly(tcp); closeQuietly(unix); });
        Thread.ofVirtual().start(() -> { copy(unix, tcp); closeQuietly(tcp); closeQuietly(unix); });
    }

    private SocketChannel connectUpstream() throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < SPAWN_RACE_RETRIES; attempt++) {
            try {
                SocketChannel unix = SocketChannel.open(StandardProtocolFamily.UNIX);
                unix.connect(upstream);
                return unix;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(SPAWN_RACE_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while connecting to " + upstream.getPath(), ie);
                }
            }
        }
        throw last;
    }

    private static void copy(SocketChannel from, SocketChannel to) {
        ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            int n;
            while ((n = from.read(buf)) != -1) {
                buf.flip();
                while (buf.hasRemaining()) {
                    to.write(buf);
                }
                buf.clear();
            }
        } catch (IOException ignored) {
            // peer closed or reset -- the caller closes both channels, unblocking the other direction
        }
    }

    /** Stop accepting and release the loopback listener. In-flight splices end when their peers close. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeQuietly(server);
        }
    }

    private static void closeQuietly(Channel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
