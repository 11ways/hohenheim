package be.elevenways.hohenheim.server;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The controller's shutdown steps are independent: one that throws is logged and the next
 * still runs.
 *
 * AIDEV-NOTE: the steps used to share one lambda, so a throwing proxy stop skipped the
 * Spamservice process-group cleanup (a setsid child that outlives the JVM outside systemd)
 * and the host lease hand-back. ServerMain's stop tears down process-wide singletons, so
 * the declared order and the step runner are what this drives; ShutdownOrderJourneyTest
 * runs the real one.
 */
class ShutdownStepTest {

    @Test
    void aThrowingStepNeverStrandsTheStepsAfterIt() {
        List<String> ran = new ArrayList<>();

        // 1. A step that throws a RuntimeException is contained.
        Throwable escaped = catchThrowable(() -> HostStep.always("failing",
            () -> {
                ran.add("failing");
                throw new IllegalStateException("listener already gone");
            }).stop());
        assertThat(escaped).as("step 1: the failure did not escape the step").isNull();

        // 2. So is an Error, the shape a wedged native resource tends to throw.
        Throwable error = catchThrowable(() -> HostStep.always("erroring",
            () -> {
                ran.add("erroring");
                throw new LinkageError("native half unloaded");
            }).stop());
        assertThat(error).as("step 2: an Error did not escape either").isNull();

        // 3. And the step after both still runs.
        HostStep.always("after", () -> ran.add("after")).stop();
        assertThat(ran).as("step 3: every step ran, in order")
            .containsExactly("failing", "erroring", "after");
    }

    @Test
    void theDeclaredOrderIsThePinnedOrder() {
        // 1. Boot: the recoveries are queued first, the stop is registered before the first managed child can start,
        //    and the ingress opens last.
        assertThat(ServerMain.bootSteps()).extracting(HostStep::name)
            .as("step 1: the steps after the boot stages")
            .containsExactly("docker probe", "stack recovery", "database adoption", "instance recovery",
                "build recovery", "database dump recovery", "release recovery", "shutdown registration", "spamservice",
                "proxy listeners", "dns", "sftp server");

        // 2. Stop: the ingress first, so no viewer subscribes to the stats streams after it; what it feeds next, then
        //    the workers; the host leases last, while the datasource is still open.
        assertThat(ServerMain.stopSteps()).extracting(HostStep::name)
            .as("step 2: the steps of the stop")
            .containsExactly("proxy listeners", "dns listeners", "instance stats streams", "secondary zone replication",
                "sftp server", "spamservice", "ssh auth watcher", "ban writer", "stack workers", "host leases");
    }
}
