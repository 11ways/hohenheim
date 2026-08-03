package be.elevenways.hohenheim.server.docker;

import java.io.IOException;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One live byte pipe to a Docker daemon, opened by {@link DockerStreamTransport}: reads
 * deliver bytes AS THEY ARRIVE (never buffer-to-EOF), writes reach the daemon after the
 * request was sent (attach stdin), and {@link #close()} releases every underlying
 * resource -- socket, subprocess, drain thread -- idempotently. Backpressure is the pull
 * model itself: a consumer that stops reading stops the daemon at the kernel's socket
 * buffer, so a slow consumer can never grow this process's heap.
 */
public interface DockerStreamConnection {

    /**
     * Blocking read of whatever is available (at least one byte), like
     * {@code InputStream.read}.
     *
     * @return the number of bytes read, or -1 at orderly EOF (the daemon closed)
     * @throws IOException on transport failure, or when the connection was closed
     *         locally while blocked (callers that closed it themselves must treat
     *         this as their own close, not a daemon failure)
     */
    int read(byte @NonNull [] buffer, int offset, int length) throws IOException;

    /** Write bytes toward the daemon (attach stdin) and flush. */
    void write(byte @NonNull [] data) throws IOException;

    /** Release the socket / subprocess / drain thread; idempotent, never throws. */
    void close();

    /**
     * Whether every underlying resource is gone (socket closed; subprocess dead and its
     * drain thread finished). The leak test counts on this being an OBSERVATION of real
     * process/socket state, not a flag set by close().
     */
    boolean isReleased();

    /**
     * Transport-side evidence for error messages: the ssh/dial-stdio stderr tail and
     * exit code where one exists, empty for a plain socket. Never null.
     */
    @NonNull String diagnostics();
}
