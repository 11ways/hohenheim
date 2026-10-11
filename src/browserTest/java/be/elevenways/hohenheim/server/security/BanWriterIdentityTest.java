package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.security.SystemPrincipal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ban writer runs every automatic ban as the system's own reaction, never as the request that tripped it, and
 * admits the task it runs plus a full queue.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class BanWriterIdentityTest {

    @BeforeAll
    static void boot() {
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void aBanTrippedDuringATenantRequestIsTheSystemsOwnAction() throws Exception {
        Executor writer = BanService.backgroundWriter();

        // 1. Work queued while a tenant request is in flight runs as system, attributed to the system.
        UserPrincipal tenant = new UserPrincipal(4343, "Ban Tripper");
        CompletableFuture<List<Object>> tripped = new CompletableFuture<>();
        TenantConduits.as(tenant, () -> writer.execute(() -> tripped.complete(observed())));
        assertThat(tripped.get(10, TimeUnit.SECONDS))
            .as("step 1: system authority, no caller, system attribution")
            .containsExactly(true, true, SystemPrincipal.INSTANCE.reference());

        // 2. Work queued with no caller at all is the system's too.
        CompletableFuture<List<Object>> unattended = new CompletableFuture<>();
        writer.execute(() -> unattended.complete(observed()));
        assertThat(unattended.get(10, TimeUnit.SECONDS))
            .as("step 2: work with no submitter is the system's")
            .containsExactly(true, true, SystemPrincipal.INSTANCE.reference());
    }

    @Test
    void aColdBurstAdmitsTheWritersOwnTaskPlusAFullQueue() throws Exception {
        // 1. A cold burst: the writer's lane is held before it runs anything, so every task submitted waits. The
        //    writer's own first task plus a full queue are all admitted, as the one-thread pool it replaced admitted.
        JobRunner lane = JobRunner.createDaemonSerial("ban-writer-burst-test");
        CountDownLatch release = new CountDownLatch(1);
        lane.fireAndForget(() -> awaitQuietly(release));
        try {
            Executor writer = BanService.backgroundWriter(lane);
            int admissible = BanService.WRITER_QUEUE_CAPACITY + 1;
            CountDownLatch ran = new CountDownLatch(admissible);
            for (int i = 0; i < admissible; i++) {
                writer.execute(ran::countDown);
            }

            // 2. One more than that is refused, as before.
            assertThatThrownBy(() -> writer.execute(ran::countDown))
                .as("step 2: the task past a full queue is refused")
                .isInstanceOf(RejectedExecutionException.class);

            // 3. Once the writer is free, every admitted task runs.
            release.countDown();
            assertThat(ran.await(10, TimeUnit.SECONDS)).as("step 3: all admitted tasks ran").isTrue();
        } finally {
            release.countDown();
            lane.shutdown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether the writer thread is system work, whether it has no caller, and its typed attribution. */
    private static List<Object> observed() {
        Accountability attribution = Accountability.current();
        return List.of(ExecutionIdentity.isSystem(), ExecutionIdentity.currentCaller() == null,
            attribution.actorReference());
    }
}
