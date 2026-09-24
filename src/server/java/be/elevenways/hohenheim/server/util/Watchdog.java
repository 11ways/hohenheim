package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * THE deadline scheduler of the daemon clients: one small pool of daemon threads that fires
 * the "close the channel / destroy the process" task of a stalled exchange.
 *
 * AIDEV-NOTE: a watchdog task only CLOSES something (a socket, a subprocess), which never
 * blocks for long, so every transport shares these threads instead of each class owning a
 * single-thread executor of its own. Two threads rather than one so that one slow
 * {@code destroyForcibly} can never delay another exchange's deadline.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class Watchdog {

    private static final AtomicInteger THREADS = new AtomicInteger();

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "hohenheim-watchdog-" + THREADS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

    private Watchdog() {
    }

    /**
     * Run {@code onExpiry} once after {@code timeoutMs} unless the returned future is
     * cancelled first; {@code isDone()} on it is how a caller tells its own timeout apart
     * from any other failure.
     */
    public static @NonNull ScheduledFuture<?> schedule(@NonNull Runnable onExpiry, long timeoutMs) {
        return SCHEDULER.schedule(onExpiry, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Run {@code sweep} every {@code intervalMs} (first after one interval) until the returned
     * future is cancelled.
     *
     * AIDEV-NOTE: a sweep here only DECIDES what has expired; anything that blocks (an
     * activity-log write, a daemon call) must be handed to its own thread, or it delays
     * every other exchange's deadline. A sweep that throws is never run again, so it
     * catches its own failures.
     */
    public static @NonNull ScheduledFuture<?> every(@NonNull Runnable sweep, long intervalMs) {
        return SCHEDULER.scheduleWithFixedDelay(sweep, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }
}
