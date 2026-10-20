package be.elevenways.hohenheim.server.proxy;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Marks a public socket's blocking call (accept, receive) so its closer can wait until the socket really let go of
 * its port.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
// AIDEV-NOTE: the JDK closes a socket that a thread is blocked on only once that thread has left the call: close()
// merely pre-closes and signals it. Until then the port stays bound, and a rebind right after close() (a stream
// switched from TCP to both transports) failed with "Address already in use" about one time in seven. Waiting for the
// call, not for the whole thread, keeps the closer free of the thread's later work and locks (a failing accept loop
// calls ProxyServer's synchronized failure handler while ProxyServer.stop() may be closing it). The wait is capped at
// LEFT_WAIT, since the closer holds the listener's monitor and possibly ProxyServer's: past it, or when interrupted
// while the call is still held, awaitLeft reports false and the caller logs that the port is not confirmed free. That
// is only a warning: the next native bind is authoritative, and a bind that does fail surfaces as a StreamBindFailure
// in StreamRelays. Work after the call, such as the UDP router resolving a new client's upstream, is never waited for.
// PublicListenerRebindTest proves it.
final class BlockingSocketCall {

    /** One blocking socket call that may throw. */
    interface BlockingCall<T> {
        T call() throws IOException;
    }

    /** How long a closer waits for the woken call to leave. */
    static final Duration LEFT_WAIT = Duration.ofSeconds(5);

    private final ReentrantLock inCall = new ReentrantLock();

    /** @return what the call returned */
    <T> T during(BlockingCall<T> call) throws IOException {
        this.inCall.lock();
        try {
            return call.call();
        } finally {
            this.inCall.unlock();
        }
    }

    /**
     * Waits until no thread is inside {@link #during}; call it after closing the socket, which wakes that thread.
     *
     * @return false when the call did not leave within {@link #LEFT_WAIT} or the wait was interrupted while it was
     *         still held, so the port may still be bound
     */
    boolean awaitLeft() {
        if (this.inCall.isHeldByCurrentThread()) {
            return true;
        }
        // AIDEV-NOTE: the free lock is taken without waiting first, because an accept loop closing itself after a
        // terminal failure is interrupted by its own runner's shutdownNow(), and the timed tryLock refuses an
        // interrupted caller even when the lock is free. The interrupt flag is left as it was.
        if (!this.inCall.tryLock()) {
            try {
                if (!this.inCall.tryLock(LEFT_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                    return false;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        this.inCall.unlock();
        return true;
    }
}
