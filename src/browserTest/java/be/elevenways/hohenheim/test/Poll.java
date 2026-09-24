package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * THE bounded wait of the browser-test suite: poll a condition until it holds or a deadline
 * passes, then fail naming what was awaited.
 *
 * AIDEV-NOTE: this replaced ~40 hand-written await/awaitTrue/waitFor copies, each with its
 * own interval, its own clock read and its own timeout shape (some returned a boolean the
 * caller forgot to assert, some fell through silently on interrupt). The deadline reads
 * {@link Now} like every other clock read in the tree. A poll is for waiting on something
 * that WILL happen; proving that something does NOT happen needs a deterministic probe (a
 * latch, a lock query), never a poll that times out or a fixed sleep.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class Poll {

    /** The interval between probes when a caller names none. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMillis(25);

    private Poll() {
    }

    /**
     * Wait until {@code condition} holds.
     *
     * @throws AssertionError naming {@code what} when the deadline passes first
     */
    public static void until(@NonNull String what, @NonNull Duration timeout,
                             @NonNull BooleanSupplier condition) {
        until(what, timeout, DEFAULT_INTERVAL, condition);
    }

    /**
     * Wait until {@code condition} holds, probing every {@code interval}.
     *
     * @throws AssertionError naming {@code what} when the deadline passes first
     */
    public static void until(@NonNull String what, @NonNull Duration timeout,
                             @NonNull Duration interval, @NonNull BooleanSupplier condition) {
        value(what, timeout, interval, () -> condition.getAsBoolean() ? Boolean.TRUE : null);
    }

    /**
     * Wait until {@code probe} answers a non-null value, and return it.
     *
     * @throws AssertionError naming {@code what} when the deadline passes first
     */
    public static <T> @NonNull T value(@NonNull String what, @NonNull Duration timeout,
                                       @NonNull Supplier<@Nullable T> probe) {
        return value(what, timeout, DEFAULT_INTERVAL, probe);
    }

    /**
     * Wait until {@code probe} answers a non-null value, probing every {@code interval}.
     *
     * The probe always runs once more AT the deadline, so a condition that became true
     * during the last sleep is never reported as a timeout.
     *
     * @throws AssertionError naming {@code what} when the deadline passes first, or when
     *         the waiting thread is interrupted
     */
    public static <T> @NonNull T value(@NonNull String what, @NonNull Duration timeout,
                                       @NonNull Duration interval,
                                       @NonNull Supplier<@Nullable T> probe) {
        long deadline = Now.millis() + timeout.toMillis();
        while (true) {
            T answer = probe.get();
            if (answer != null) {
                return answer;
            }
            long remaining = deadline - Now.millis();
            if (remaining <= 0) {
                throw new AssertionError("timed out after " + timeout.toMillis()
                    + "ms waiting for: " + what);
            }
            try {
                Thread.sleep(Math.min(interval.toMillis(), remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for: " + what, interrupted);
            }
        }
    }
}
