package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test-only interleaving point inside a site write, riding the public
 * {@code Schema.addBeforeValidateHook} seam with zero scaffolding in production code.
 *
 * It parks the designated thread AFTER the advisory route scan (installed at the MODULES
 * boot stage, so it is registered first and runs first) and BEFORE the authoritative
 * live-route claim in the before-write tier. That is exactly the window the takeover race
 * lives in: a worker parked here has provably passed its conflict scan and has written
 * nothing yet, so a second enable can run to completion around it and the released worker
 * then acts on a stale read.
 *
 * AIDEV-NOTE: Schema has no hook-REMOVAL API and SiteModel.SCHEMA is static, so the hook
 * is installed ONCE per JVM and driven through a volatile coordinator reference -- tests
 * MUST null it out (clear()) or it parks unrelated site writes in the shared-server JVM.
 * Parking before any write is also what keeps this safe on SQLite, whose datasource shares
 * ONE JDBC connection between threads: a parked worker holds no uncommitted rows, so the
 * other worker's commit cannot carry half of it along.
 */
final class SiteEnableWriteBarrier {

    private static volatile Coordinator active;

    static {
        SiteModel.SCHEMA.addBeforeValidateHook(context -> {
            Coordinator coordinator = active;
            if (coordinator != null) {
                coordinator.onBeforeValidate();
            }
        });
    }

    private SiteEnableWriteBarrier() {
    }

    static void install(Coordinator coordinator) {
        active = coordinator;
    }

    static void clear() {
        active = null;
    }

    /** Parks ONE designated thread until the test releases it. */
    static final class Coordinator {

        private final CountDownLatch parked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread held;

        void holdWriteOf(Thread thread) {
            this.held = thread;
        }

        /** @return true when the held worker reached the barrier within the timeout */
        boolean awaitParked() throws InterruptedException {
            return this.parked.await(10, TimeUnit.SECONDS);
        }

        void releaseHeldWriter() {
            this.release.countDown();
        }

        void onBeforeValidate() {
            if (Thread.currentThread() != this.held) {
                return;
            }
            // Park only the FIRST write of the held worker; a retry or a follow-up save on
            // the same thread must run straight through.
            this.held = null;
            this.parked.countDown();
            try {
                if (!this.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The held site writer was never released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted at the site write barrier", error);
            }
        }
    }
}
