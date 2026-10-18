package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.time.Backoff;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.Pace;
import be.elevenways.protoblast.common.time.Retry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * THE bounded wait of the browser-test suite: poll a condition until it holds or a deadline
 * passes, then fail naming what was awaited.
 *
 * AIDEV-NOTE: this replaced ~40 hand-written await/awaitTrue/waitFor copies, each with its
 * own interval, its own clock read and its own timeout shape (some returned a boolean the
 * caller forgot to assert, some fell through silently on interrupt). A poll is for waiting
 * on something that WILL happen; proving that something does NOT happen needs a
 * deterministic probe (a latch, a lock query), never a poll that times out or a fixed sleep.
 *
 * AIDEV-NOTE: the loop is protoblast's {@link Retry} over a fixed {@link Backoff}: a probe
 * with nothing to say yet is a failed attempt, and every wait between probes is a
 * {@link Pace} wait on {@link Now}, so a test moving the clock offset ends it. This class
 * only names what was awaited; it never grows a loop or a sleep of its own.
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
        latest(what, window, interval, () -> {
            if (violation.getAsBoolean()) {
                throw new AssertionError("happened within the " + window.toMillis()
                    + "ms window, but must not: " + what);
            }
            return null;
        }, nothing -> false);
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
     * @throws AssertionError naming {@code what} when the deadline passes first, or when
     *         the waiting thread is interrupted
     */
    public static <T> @NonNull T value(@NonNull String what, @NonNull Duration timeout,
                                       @NonNull Duration interval,
                                       @NonNull Supplier<@Nullable T> probe) {
        T answer = latest(what, timeout, interval, probe, any -> true);
        if (answer == null) {
            throw new AssertionError("timed out after " + timeout.toMillis()
                + "ms waiting for: " + what);
        }
        return answer;
    }

    /**
     * Probe every {@code interval} until {@code accepted} holds for an answer, for a caller
     * that asserts on (or reports) what it saw last instead of failing at the deadline.
     *
     * The probe always runs once more AT the deadline, so an answer that arrived during the
     * last wait is never missed; a null answer says nothing and never replaces an earlier
     * one. A probe that throws ends the poll at once with that failure.
     *
     * @return the first accepted answer, else the last non-null one, null when none came
     * @throws AssertionError naming {@code what} when the polling thread is interrupted
     */
    public static <T> @Nullable T latest(@NonNull String what, @NonNull Duration timeout,
                                         @NonNull Duration interval,
                                         @NonNull Supplier<@Nullable T> probe,
                                         @NonNull Predicate<@NonNull T> accepted) {
        Instant deadline = Now.instant().plus(timeout);
        Answers<T> answers = new Answers<>(probe, accepted);
        Retry retry = Retry.of(Backoff.fixed(interval), Integer.MAX_VALUE)
            .judgedBy(failure -> failure instanceof NotYet ? Retry.Verdict.AGAIN : Retry.Verdict.FINAL);
        try {
            return timeout.isPositive() ? retry.within(timeout).run(answers::next) : answers.next();
        } catch (NotYet notYet) {
            // Retry stops once its next wait would overrun the deadline: wait out the rest, then look once more.
            try {
                Pace.until(deadline);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while polling for: " + what, interrupted);
            }
        }
        try {
            return answers.next();
        } catch (NotYet stillNot) {
            return answers.last;
        }
    }

    /** The probe had nothing accepted to say yet: the attempt {@link Retry} runs again. */
    private static final class NotYet extends Exception {

        NotYet() {
            super("not yet", null, false, false);
        }
    }

    /** One poll's probe and the last non-null answer it gave. */
    private static final class Answers<T> {

        private final @NonNull Supplier<@Nullable T> probe;
        private final @NonNull Predicate<@NonNull T> accepted;
        private @Nullable T last;

        private Answers(@NonNull Supplier<@Nullable T> probe, @NonNull Predicate<@NonNull T> accepted) {
            this.probe = probe;
            this.accepted = accepted;
        }

        /** @throws NotYet when the answer is null or not accepted */
        private @NonNull T next() throws NotYet {
            T answer = this.probe.get();
            if (answer != null) {
                this.last = answer;
                if (this.accepted.test(answer)) {
                    return answer;
                }
            }
            throw new NotYet();
        }
    }
}
