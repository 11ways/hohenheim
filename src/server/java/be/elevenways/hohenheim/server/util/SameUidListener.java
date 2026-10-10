package be.elevenways.hohenheim.server.util;

import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;

/**
 * THE loopback TCP listener only this process's own uid may use, behind every bridge that splices what connects to it.
 *
 * AIDEV-NOTE: each accepted connection is judged by {@link LoopbackPeers#admits} on its own virtual thread, so one
 * slow kernel-table read never holds the next accept; a refused peer is closed before the consumer sees it.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since  0.1.0
 */
public final class SameUidListener {

    private final ServerSocketChannel server;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final @NonNull String bridge;
    private final @NonNull BinaryOperator<Integer> peerOwner;
    private final @Nullable Integer selfUid;
    private final @NonNull Consumer<SocketChannel> admitted;

    /**
     * Binds {@code 127.0.0.1:0} and starts accepting.
     *
     * @param threadName the accept thread's name, before its port
     * @param bridge     the bridge's name and what it guards, for the log lines
     * @param peerOwner  (peer port, bridge port) to the uid owning the connecting socket, null when unknown
     * @param selfUid    the uid a peer must own its socket as; null refuses every peer
     * @param admitted   takes over an admitted connection, closing it when done
     */
    public SameUidListener(@NonNull String threadName, @NonNull String bridge,
                           @NonNull BinaryOperator<Integer> peerOwner, @Nullable Integer selfUid,
                           @NonNull Consumer<SocketChannel> admitted) throws IOException {
        this.bridge = bridge;
        this.peerOwner = peerOwner;
        this.selfUid = selfUid;
        this.admitted = admitted;
        this.server = ServerSocketChannel.open();
        this.server.bind(new InetSocketAddress("127.0.0.1", 0));
        this.port = ((InetSocketAddress) this.server.getLocalAddress()).getPort();
        Thread.ofVirtual().name(threadName + port).start(this::acceptLoop);
    }

    /** The loopback TCP port a client dials. */
    public int port() {
        return port;
    }

    private void acceptLoop() {
        while (!closed.get()) {
            SocketChannel tcp;
            try {
                tcp = server.accept();
            } catch (IOException e) {
                if (!closed.get()) {
                    Blast.log(bridge + " accept failed:", e.getMessage());
                }
                return;
            }
            Thread.ofVirtual().start(() -> {
                if (LoopbackPeers.admits(tcp, this.port, this.peerOwner, this.selfUid, this.bridge)) {
                    admitted.accept(tcp);
                } else {
                    Closeables.closeQuietly(tcp);
                }
            });
        }
    }

    /** Stop accepting and release the listener; connections already handed over are their consumer's. */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Closeables.closeQuietly(server);
        }
    }
}
