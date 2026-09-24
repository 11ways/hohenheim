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
     * Assert {@code violation} stays false for the whole {@code window}, sampling every
     * {@link #DEFAULT_INTERVAL}; see {@link #never(String, Duration, Duration, BooleanSupplier)}.
     *
     * @throws AssertionError naming {@code what} on the first sample that saw it happen
     */
    public static void never(@NonNull String what, @NonNull Duration window,
                             @NonNull BooleanSupplier violation) {
        never(what, window, DEFAULT_INTERVAL, violation);
    }

    /**
     * Assert {@code violation} stays false for the whole {@code window}, sampling every
     * {@code interval} and failing on the FIRST sample that sees it true.
     *
     * AIDEV-NOTE: the LAST resort for "nothing happened", for a negative with no
     * deterministic signal to wait on (the class docblock's latch or lock query). It
     * replaced fixed sleeps followed by one look, which saw only the window's final instant:
     * a violation that happened and undid itself inside the sleep read as a pass, and a
     * violation early in the window still cost the whole sleep. It samples throughout,
     * fails fast, and probes once more AT the window's end. A window proves only what it
     * covers, so size it to the thing that must NOT arrive, never to what makes CI green.
     *
     * @throws AssertionError naming {@code what} on the first violating sample, or when the
     *         sampling thread is interrupted
     */
    public static void never(@NonNull String what, @NonNull Duration window,
                             @NonNull Duration interval, @NonNull BooleanSupplier violation) {
        long deadline = Now.millis() + window.toMillis();
        while (true) {
            if (violation.getAsBoolean()) {
                throw new AssertionError("happened within the " + window.toMillis()
                    + "ms window, but must not: " + what);
            }
            long remaining = deadline - Now.millis();
            if (remaining <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(interval.toMillis(), remaining));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while watching for: " + what, interrupted);
            }
        }
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
