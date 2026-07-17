package be.elevenways.hohenheim.server.devtunnel;

import be.elevenways.protoblast.common.Blast;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback TCP listener that turns each connection from Undertow's proxy
 * client into one multiplexed tunnel stream (the tunnel sibling of
 * UnixSocketBridge). One bridge exists per live dev lease.
 */
final class DevTunnelBridge {

    private final ServerSocketChannel server;
    private final int port;
    private final DevTunnelServerHandler handler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DevTunnelBridge(DevTunnelServerHandler handler) throws IOException {
        this.handler = handler;
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
            try {
                handler.openStream(tcp);
            } catch (RuntimeException e) {
                Blast.log("DevTunnelBridge could not open a tunnel stream:", e.getMessage());
                closeQuietly(tcp);
            }
        }
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
