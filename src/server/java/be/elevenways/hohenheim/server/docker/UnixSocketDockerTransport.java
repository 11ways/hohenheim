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
 * Also the local {@link DockerStreamTransport}: a stream is the same channel WITHOUT
 * the read-to-EOF discipline, living until either side closes it.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class UnixSocketDockerTransport implements DockerTransport, DockerStreamTransport {

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

    @Override
    public DockerStreamConnection openStream(byte[] request, long connectTimeoutMs)
            throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        // The watchdog covers connect + request write ONLY: once the stream is
        // handed over, its lifetime is the consumer's decision, not a deadline's.
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(
            () -> closeQuietly(channel), connectTimeoutMs, TimeUnit.MILLISECONDS);
        try {
            channel.connect(address);
            writeFully(channel, ByteBuffer.wrap(request));
        } catch (ClosedChannelException e) {
            throw new IOException("Docker stream connect timed out after " + connectTimeoutMs + "ms");
        } catch (IOException e) {
            closeQuietly(channel);
            throw e;
        } finally {
            watchdog.cancel(false);
        }
        return new SocketStreamConnection(channel);
    }

    /** Blocking channel pipe; close() from any thread unblocks a pending read. */
    private static final class SocketStreamConnection implements DockerStreamConnection {

        private final SocketChannel channel;

        SocketStreamConnection(SocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ByteBuffer slice = ByteBuffer.wrap(buffer, offset, length);
            return this.channel.read(slice);
        }

        @Override
        public void write(byte[] data) throws IOException {
            writeFully(this.channel, ByteBuffer.wrap(data));
        }

        @Override
        public void close() {
            closeQuietly(this.channel);
        }

        @Override
        public boolean isReleased() {
            return !this.channel.isOpen();
        }

        @Override
        public String diagnostics() {
            return "";
        }
    }
}
