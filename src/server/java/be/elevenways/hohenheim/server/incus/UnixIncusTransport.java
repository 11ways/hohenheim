package be.elevenways.hohenheim.server.incus;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;

/**
 * The local Incus lane: the daemon's unix socket, trusted by file access exactly like
 * the local Docker socket. Same framing, same websocket lane, no TLS -- there is no
 * wire identity to pin on a socket the kernel already scopes to root.
 */
final class UnixIncusTransport extends StreamIncusTransport {

    /** Default Incus daemon socket. */
    static final String DEFAULT_SOCKET = "/var/lib/incus/unix.socket";

    private final @NonNull String socketPath;

    UnixIncusTransport(@NonNull String socketPath) {
        this.socketPath = socketPath;
    }

    @Override
    protected @NonNull Channel open(long connectTimeoutMs) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.connect(UnixDomainSocketAddress.of(this.socketPath));
        } catch (IOException e) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // already failing
            }
            throw e;
        }
        InputStream in = Channels.newInputStream(channel);
        OutputStream out = Channels.newOutputStream(channel);
        return new Channel() {
            @Override
            public @NonNull InputStream in() {
                return in;
            }

            @Override
            public @NonNull OutputStream out() {
                return out;
            }

            @Override
            public void close() throws IOException {
                channel.close();
            }
        };
    }

    @Override
    protected @NonNull String hostHeader() {
        return "incus";
    }

    @Override
    public @NonNull String describe() {
        return "unix://" + this.socketPath;
    }
}
