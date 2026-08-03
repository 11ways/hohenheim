package be.elevenways.hohenheim.server.docker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The default {@link DockerTransport}: HTTP/1.1 over the daemon's local unix socket.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class UnixSocketDockerTransport implements DockerTransport {

    // AIDEV-NOTE: A blocking SocketChannel over AF_UNIX can't use Socket.setSoTimeout, so we bound
    // each request with a watchdog that closes the channel on expiry; the blocked connect/read then
    // throws ClosedChannelException, surfaced as a timeout.
    private static final ScheduledExecutorService WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "docker-unix-watchdog");
            thread.setDaemon(true);
            return thread;
        });

    private final UnixDomainSocketAddress address;

    public UnixSocketDockerTransport(String socketPath) {
        this.address = UnixDomainSocketAddress.of(socketPath);
    }

    @Override
    public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
        return roundTrip(request, timeoutMs, Long.MAX_VALUE);
    }

    @Override
    public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            () -> closeQuietly(channel), timeoutMs, TimeUnit.MILLISECONDS);
        try {
            channel.connect(address);
            writeFully(channel, ByteBuffer.wrap(request));
            return readToEnd(channel, maxResponseBytes);
        } catch (ClosedChannelException e) {
            throw new IOException("Docker request timed out after " + timeoutMs + "ms");
        } finally {
            watchdog.cancel(false);
            closeQuietly(channel);
        }
    }

    private static void writeFully(SocketChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }

    private static byte[] readToEnd(SocketChannel channel, long maxResponseBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(8192);
        try {
            int n;
            while ((n = channel.read(buf)) != -1) {
                buf.flip();
                out.write(buf.array(), buf.arrayOffset(), n);
                buf.clear();
                if (out.size() > maxResponseBytes) {
                    // Abort DURING the read: the cap protects the heap, so checking
                    // after readToEnd would be a check that cannot fire in time.
                    throw new IOException("Docker response exceeded the configured cap of "
                        + maxResponseBytes + " bytes");
                }
            }
        } catch (SocketException e) {
            // AIDEV-NOTE: Docker RSTs some Connection: close streams (notably /build) after sending
            // the full response, rather than a clean FIN. Keep what we read and let parsing validate
            // completeness; only a reset with nothing buffered is a real failure. (Watchdog timeouts
            // surface as ClosedChannelException, handled separately.)
            if (out.size() == 0) {
                throw e;
            }
        }
        return out.toByteArray();
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
