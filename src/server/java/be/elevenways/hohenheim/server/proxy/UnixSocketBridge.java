package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.util.LoopbackPeers;
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
import java.util.function.BinaryOperator;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Loopback TCP-to-AF_UNIX bridge: binds {@code 127.0.0.1:0} and splices each accepted TCP connection
 * to a fresh AF_UNIX connection to the configured socket path. This lets Undertow's TCP-only proxy
 * client reach a unix-socket upstream by dialing the bridge's loopback port.
 *
 * This is the correct transport, not a workaround: Undertow 2.3 over xnio-nio has no AF_UNIX client
 * support (the JNI xnio-native provider is absent), so there is no clean ClientProvider path. Undertow
 * stays fully async on its side; the raw byte-copy splice runs on virtual threads where blocking I/O
 * is cheap. Reuses the AF_UNIX {@code SocketChannel.open(UNIX)} idiom proven by UnixSocketDockerTransport.
 *
 * AIDEV-NOTE: ONLY THIS PROCESS'S OWN UID MAY USE THE BRIDGE. The loopback port is reachable by
 * every local account, and splicing any of them onto the AF_UNIX socket bypassed both the socket's
 * file permissions and the site's gates (the bypass class the socket front closed in ProxyScheme).
 * Each accepted connection's owning uid is read from the kernel's socket tables
 * ({@link LoopbackPeers}) and a peer that is not this process's uid, or cannot be identified, is
 * closed before a single byte reaches the upstream. A same-uid process already holds everything
 * the bridge could give it (the socket permissions hohenheim itself dials with), so this closes
 * the bypass without a handshake the TCP-only proxy client could not perform. A claim-by-port
 * scheme was considered and rejected: Undertow completes a TLS/ALPN dial only after the handshake,
 * which a bridge waiting for the claim would deadlock.
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
    private final BinaryOperator<Integer> peerOwner;
    private final @Nullable Integer selfUid;

    public UnixSocketBridge(String socketPath) throws IOException {
        this(socketPath, false);
    }

    /**
     * @param verifyReachable when true, prove the AF_UNIX upstream ANSWERS before the bridge
     *        exists, closing the loopback listener and throwing when it does not. Callers that
     *        CACHE a bridge keyed by an externally-influenced path ({@code AddressUpstreamKind
     *        .bridgeFor}) pass true so an unreachable path is never cached as a permanent
     *        listener + accept thread; the process-lifecycle caller passes false because its
     *        failure path releases but does not kill the child, and a still-binding child must
     *        not fail the spawn.
     */
    public UnixSocketBridge(String socketPath, boolean verifyReachable) throws IOException {
        this(socketPath, verifyReachable, LoopbackPeers::ownerOf, LoopbackPeers.selfUid());
    }

    /**
     * @param peerOwner (peer port, bridge port) to the uid owning the connecting socket, null
     *                  when unknown
     * @param selfUid   the uid a peer must own its socket as; null refuses every peer
     */
    UnixSocketBridge(String socketPath, boolean verifyReachable, BinaryOperator<Integer> peerOwner,
                     @Nullable Integer selfUid) throws IOException {
        this.peerOwner = peerOwner;
        this.selfUid = selfUid;
        this.upstream = UnixDomainSocketAddress.of(socketPath);
        this.server = ServerSocketChannel.open();
        this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        this.port = ((InetSocketAddress) this.server.getLocalAddress()).getPort();
        if (verifyReachable) {
            try {
                // connectUpstream already applies the spawn-race retries a request uses, so a
                // child still binding is tolerated; a genuinely absent path throws here.
                closeQuietly(connectUpstream());
            } catch (IOException unreachable) {
                closeQuietly(this.server);
                throw unreachable;
            }
        }
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
            Thread.ofVirtual().start(() -> {
                if (admits(tcp)) {
                    spliceToUpstream(tcp);
                } else {
                    closeQuietly(tcp);
                }
            });
        }
    }

    /** Whether the connecting socket belongs to this process's own uid. */
    private boolean admits(SocketChannel tcp) {
        Integer owner;
        try {
            if (!(tcp.getRemoteAddress() instanceof InetSocketAddress peer)) {
                return false;
            }
            owner = this.selfUid == null ? null : this.peerOwner.apply(peer.getPort(), this.port);
        } catch (IOException | RuntimeException unknown) {
            owner = null;
        }
        if (owner != null && owner.equals(this.selfUid)) {
            return true;
        }
        Blast.log("UnixSocketBridge: refused a loopback peer on port", this.port,
            "owned by uid", owner, "- only this process's uid may use the bridge to", upstream.getPath());
        return false;
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
