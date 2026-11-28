package be.elevenways.hohenheim.server.cluster;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import be.elevenways.protoblast.common.time.Pace;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One piece of background work asked for again and again: at most one run at a time, and every ask that arrives
 * while it runs is answered by exactly one more run after it.
 *
 * AIDEV-NOTE: the count is the asks not yet answered by a run that started after them, never a busy flag: a run takes
 * the count it saw when it started and subtracts it when it ends, so an ask during a run leaves the count above zero
 * and the same thread runs once more. protoblast has no such primitive (SerialLane drops work when full, JobRunner
 * runs every submission); this is its one home in Hohenheim until protoblast offers one.
 * AIDEV-NOTE: every ask is numbered ({@link #asked()}), and a run answers every ask numbered before it started, so a
 * wait for an ask ({@link #awaitAnswered}) ends only after a run that STARTED after it ended, never on a run already
 * under way when it was asked.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class Coalesced {

    private final JobRunnerGroup.Member member;
    private final Runnable work;
    private final AtomicInteger asked = new AtomicInteger();
    private final AtomicLong numbered = new AtomicLong();
    private final AtomicLong answered = new AtomicLong();
    private final Map<Long, CompletableFuture<Void>> waiting = new ConcurrentSkipListMap<>();

    /**
     * @param member the runtime pool its runs start on
     * @param work   the work; a failure of one run is logged and ends nothing but that run
     */
    Coalesced(JobRunnerGroup.@NonNull Member member, @NonNull Runnable work) {
        this.member = Objects.requireNonNull(member, "member");
        this.work = Objects.requireNonNull(work, "work");
    }

    /** Asks for a run: one starts now when none runs, else the running one is followed by exactly one more. */
    void ask() {
        this.numbered.incrementAndGet();
        if (this.asked.getAndIncrement() > 0) {
            return;
        }
        try {
            this.member.get().runAsync(this::drain);
        } catch (IllegalStateException stopping) {
            // The runtime stops: nothing more runs, and a later runtime starts from no pending ask.
            this.asked.set(0);
        }
    }

    /** @return the number of the latest ask, 0 before any */
    long asked() {
        return this.numbered.get();
    }

    /**
     * Waits until a run that started after ask {@code number} ended, for tests.
     *
     * @return false when none had ended at the timeout, or the wait was interrupted
     */
    boolean awaitAnswered(long number, @NonNull Duration timeout) {
        if (this.answered.get() >= number) {
            return true;
        }
        CompletableFuture<Void> done = this.waiting.computeIfAbsent(number, ignored -> new CompletableFuture<>());
        if (this.answered.get() >= number) {
            done.complete(null);
        }
        try {
            Pace.await(done, timeout);
            return true;
        } catch (TimeoutException | ExecutionException late) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void drain() {
        int seen;
        do {
            seen = this.asked.get();
            long covers = this.numbered.get();
            try {
                this.work.run();
            } catch (RuntimeException failed) {
                Blast.log("CLUSTER: background work failed -", failed.getClass().getSimpleName());
            }
            this.answered.accumulateAndGet(covers, Math::max);
            this.waiting.entrySet().removeIf(entry -> {
                if (entry.getKey() > covers) {
                    return false;
                }
                entry.getValue().complete(null);
                return true;
            });
        } while (this.asked.addAndGet(-seen) > 0);
    }
}
