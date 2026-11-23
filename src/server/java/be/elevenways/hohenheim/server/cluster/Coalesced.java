package be.elevenways.hohenheim.server.cluster;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunnerGroup;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One piece of background work asked for again and again: at most one run at a time, and every ask that arrives
 * while it runs is answered by exactly one more run after it.
 *
 * AIDEV-NOTE: the count is the asks not yet answered by a run that started after them, never a busy flag: a run takes
 * the count it saw when it started and subtracts it when it ends, so an ask during a run leaves the count above zero
 * and the same thread runs once more. protoblast has no such primitive (SerialLane drops work when full, JobRunner
 * runs every submission); this is its one home in Hohenheim until protoblast offers one.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class Coalesced {

    private final JobRunnerGroup.Member member;
    private final Runnable work;
    private final AtomicInteger asked = new AtomicInteger();

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

    private void drain() {
        int seen;
        do {
            seen = this.asked.get();
            try {
                this.work.run();
            } catch (RuntimeException failed) {
                Blast.log("CLUSTER: background work failed -", failed.getClass().getSimpleName());
            }
        } while (this.asked.addAndGet(-seen) > 0);
    }
}
