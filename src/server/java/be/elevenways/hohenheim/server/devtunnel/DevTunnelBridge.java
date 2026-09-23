package be.elevenways.hohenheim.server.devtunnel;

import be.elevenways.hohenheim.server.util.LoopbackPeers;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BinaryOperator;

/**
 * Loopback TCP listener that turns each connection from Undertow's proxy
 * client into one multiplexed tunnel stream (the tunnel sibling of
 * UnixSocketBridge). One bridge exists per live dev lease.
 *
 * AIDEV-NOTE: ONLY THIS PROCESS'S OWN UID MAY USE THE BRIDGE, exactly like UnixSocketBridge.
 * The loopback port is reachable by every local account on the controller host, and a
 * connection spliced onto the tunnel reaches a DEVELOPER'S machine past every proxy-level
 * rule (access lists, auth gates, the offline page). Each accepted connection's owning uid is
 * read from the kernel's socket tables ({@link LoopbackPeers}); a peer that is not this
 * process's uid, or cannot be identified, is closed before a stream is opened. A per-bridge
 * secret the proxy presents was considered and rejected: the proxy client speaks plain HTTP to
 * the bridge, so a secret would mean parsing and rewriting request heads inside a byte splice,
 * while a same-uid process already holds the registration token that opens a tunnel at all.
 */
final class DevTunnelBridge {

    private final ServerSocketChannel server;
    private final int port;
    private final DevTunnelServerHandler handler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final BinaryOperator<Integer> peerOwner;
    private final @Nullable Integer selfUid;

    DevTunnelBridge(DevTunnelServerHandler handler) throws IOException {
        this(handler, LoopbackPeers::ownerOf, LoopbackPeers.selfUid());
    }

    /**
     * @param peerOwner (peer port, bridge port) to the uid owning the connecting socket, null
     *                  when unknown
     * @param selfUid   the uid a peer must own its socket as; null refuses every peer
     */
    DevTunnelBridge(DevTunnelServerHandler handler, BinaryOperator<Integer> peerOwner,
                    @Nullable Integer selfUid) throws IOException {
        this.handler = handler;
        this.peerOwner = peerOwner;
        this.selfUid = selfUid;
        this.server = ServerSocketChannel.open();
        this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        this.port = ((InetSocketAddress) this.server.getLocalAddress()).getPort();
        Thread.ofVirtual().name("dev-tunnel-bridge-" + port).start(this::acceptLoop);
    }

    int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (!closed.get()) {
            SocketChannel tcp;
            try {
                tcp = server.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    Blast.log("DevTunnelBridge accept failed:", e.getMessage());
                }
                return;
            }
            // The kernel-table read happens off the accept loop, so one slow read never
            // holds the next connection.
            Thread.ofVirtual().start(() -> {
                if (!admits(tcp)) {
                    closeQuietly(tcp);
                    return;
                }
                try {
                    handler.openStream(tcp);
                } catch (RuntimeException e) {
                    Blast.log("DevTunnelBridge could not open a tunnel stream:", e.getMessage());
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
        Blast.log("DevTunnelBridge: refused a loopback peer on port", this.port,
            "owned by uid", owner, "- only this process's uid may reach the dev tunnel");
        return false;
    }

    /** Stop accepting; in-flight streams are torn down by the handler. */
    void close() {
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
