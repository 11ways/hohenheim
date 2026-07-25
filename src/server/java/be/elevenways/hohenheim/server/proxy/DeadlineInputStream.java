package be.elevenways.hohenheim.server.proxy;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** Enforces one monotonic deadline across every blocking read from a socket. */
final class DeadlineInputStream extends FilterInputStream {

    private final Socket socket;
    private final long deadlineNanos;
    private boolean enabled = true;

    DeadlineInputStream(InputStream input, Socket socket, long timeoutMillis) {
        super(input);
        this.socket = socket;
        this.deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    @Override
    public int read() throws IOException {
        applyRemainingTimeout();
        return super.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        applyRemainingTimeout();
        return super.read(bytes, offset, length);
    }

    private void applyRemainingTimeout() throws IOException {
        if (!enabled) return;
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new SocketTimeoutException("TLS ClientHello deadline exceeded");
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        socket.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, millis + 1)));
    }

    /** Disables the handshake deadline after the complete ClientHello has been parsed. */
    void disableDeadline() throws SocketException {
        enabled = false;
        socket.setSoTimeout(0);
    }
}
