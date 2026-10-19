package be.elevenways.hohenheim.server.util;

import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.thread.ScheduledJob;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * THE deadline scheduler of the daemon clients: one process-global runner that fires the "close the channel /
 * destroy the process" task of a stalled exchange.
 *
 * AIDEV-NOTE: a watchdog task only CLOSES something (a socket, a subprocess), which never blocks for long, so every
 * transport shares this runner instead of each class owning a timer of its own. The runner's timer hands every due
 * task to its pool, so one slow {@code destroyForcibly} can never delay another exchange's deadline. Every task runs
 * detached (a DETACHED runner): a deadline or a sweep is this runner's own work, never the continuation of the
 * request that armed it.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class Watchdog {

    private static final JobRunner RUNNER = JobRunner.createProcessGlobal("hohenheim-watchdog",
        JobRunner.CallerContext.DETACHED);

    private Watchdog() {
    }

    /** One armed deadline. */
    public static final class Deadline {

        private final AtomicBoolean expired = new AtomicBoolean();
        private volatile @Nullable ScheduledJob job;

        private Deadline() {
        }

        /** @return whether the deadline fired; how a caller tells its own timeout apart from any other failure */
        public boolean expired() {
            return this.expired.get();
        }

        /** Disarms it; a no-op once it fired. */
        public void cancel() {
            ScheduledJob armed = this.job;
            if (armed != null) {
                armed.cancel();
            }
        }
    }

    /** Run {@code onExpiry} once after {@code timeoutMs} unless the returned deadline is cancelled first. */
    public static @NonNull Deadline schedule(@NonNull Runnable onExpiry, long timeoutMs) {
        Deadline deadline = new Deadline();
        deadline.job = RUNNER.schedule(() -> {
            deadline.expired.set(true);
            onExpiry.run();
        }, timeoutMs);
        return deadline;
    }

    /**
     * Run {@code sweep} every {@code intervalMs} (first after one interval) until the returned job is cancelled.
     *
     * AIDEV-NOTE: a sweep here only DECIDES what has expired; anything that blocks (an activity-log write, a daemon
     * call) must be handed to its own thread, or it holds a pool thread every interval. A sweep that throws is
     * reported to the uncaught-exception handler and runs again at its next interval.
     */
    public static @NonNull ScheduledJob every(@NonNull Runnable sweep, long intervalMs) {
        return RUNNER.scheduleRepeating(sweep, intervalMs);
    }
}
