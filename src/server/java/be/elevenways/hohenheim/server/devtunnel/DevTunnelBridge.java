package be.elevenways.hohenheim.server.devtunnel;

import be.elevenways.hohenheim.server.util.LoopbackPeers;
import be.elevenways.hohenheim.server.util.SameUidListener;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.util.Closeables;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.channels.SocketChannel;
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

    private final DevTunnelServerHandler handler;
    private final SameUidListener listener;

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
        this.listener = new SameUidListener("dev-tunnel-bridge-", "DevTunnelBridge (the dev tunnel)", peerOwner,
            selfUid, this::openStream);
    }

    int getPort() {
        return listener.port();
    }

    private void openStream(SocketChannel tcp) {
        try {
            handler.openStream(tcp);
        } catch (RuntimeException e) {
            Blast.log("DevTunnelBridge could not open a tunnel stream:", e.getMessage());
            Closeables.closeQuietly(tcp);
        }
    }

    /** Stop accepting; in-flight streams are torn down by the handler. */
    void close() {
        listener.close();
    }
}
