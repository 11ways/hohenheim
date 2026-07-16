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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** AF_UNIX front listener that splices accepted clients into a loopback TCP listener. */
public final class UnixSocketListenerBridge {

    private static final int BUFFER_SIZE = 16384;

    private final Path socketPath;
    private final InetSocketAddress upstream;
    private final ServerSocketChannel server;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public UnixSocketListenerBridge(Path socketPath, int upstreamPort, String permissions) throws IOException {
        this.socketPath = socketPath.toAbsolutePath();
        this.upstream = new InetSocketAddress("127.0.0.1", upstreamPort);
        Path parent = this.socketPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        removeStaleSocket(this.socketPath);
        ServerSocketChannel opened = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            opened.bind(UnixDomainSocketAddress.of(this.socketPath));
            Files.setPosixFilePermissions(this.socketPath, parsePermissions(permissions));
        } catch (IOException | RuntimeException failure) {
            closeQuietly(opened);
            Files.deleteIfExists(this.socketPath);
            throw failure;
        }
        this.server = opened;
        Thread.ofVirtual().name("unix-front-accept-" + this.socketPath.getFileName()).start(this::acceptLoop);
    }

    private static Set<PosixFilePermission> parsePermissions(String value) {
        String normalized = value != null ? value.trim() : "";
        if (!normalized.matches("0?[0-7]{3}")) {
            throw new IllegalArgumentException("Unix socket permissions must be a three-digit octal mode");
        }
        int mode = Integer.parseInt(normalized, 8);
        PosixFilePermission[] bits = {
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE
        };
        Set<PosixFilePermission> result = EnumSet.noneOf(PosixFilePermission.class);
        for (int i = 0; i < bits.length; i++) {
            if ((mode & (1 << (8 - i))) != 0) result.add(bits[i]);
        }
        return result;
    }

    public Path getSocketPath() {
        return socketPath;
    }

    private static void removeStaleSocket(Path path) throws IOException {
        if (!Files.exists(path)) return;
        boolean active = false;
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(path));
            active = true;
        } catch (IOException ignored) {
            // An unconnectable socket file is debris from a previous unclean stop.
        }
        if (active) throw new IOException("Unix socket is already in use: " + path);
        Files.delete(path);
    }

    private void acceptLoop() {
        while (!closed.get()) {
            SocketChannel unix;
            try {
                unix = server.accept();
            } catch (IOException e) {
                if (!closed.get()) Blast.log("Unix front listener failed:", e.getMessage());
                return;
            }
            splice(unix);
        }
    }

    private void splice(SocketChannel unix) {
        try {
            SocketChannel tcp = SocketChannel.open(upstream);
            Thread.ofVirtual().start(() -> { copy(unix, tcp); closeQuietly(unix); closeQuietly(tcp); });
            Thread.ofVirtual().start(() -> { copy(tcp, unix); closeQuietly(unix); closeQuietly(tcp); });
        } catch (IOException e) {
            closeQuietly(unix);
        }
    }

    private static void copy(SocketChannel from, SocketChannel to) {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            int count;
            while ((count = from.read(buffer)) != -1) {
                buffer.flip();
                while (buffer.hasRemaining()) to.write(buffer);
                buffer.clear();
            }
        } catch (IOException ignored) {
            // The peer closed; the splice owner closes both channels.
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeQuietly(server);
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            Blast.log("Could not remove Unix proxy socket", socketPath, "-", e.getMessage());
        }
    }

    private static void closeQuietly(Channel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
