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
 * and the host lease hand-back. ServerMain.shutdown itself tears down process-wide
 * singletons, so the step runner it is built from is what this drives.
 */
class ShutdownStepTest {

    @Test
    void aThrowingStepNeverStrandsTheStepsAfterIt() {
        List<String> ran = new ArrayList<>();

        // 1. A step that throws a RuntimeException is contained.
        Throwable escaped = catchThrowable(() -> ServerMain.shutdownStep("failing",
            () -> {
                ran.add("failing");
                throw new IllegalStateException("listener already gone");
            }));
        assertThat(escaped).as("step 1: the failure did not escape the step").isNull();

        // 2. So is an Error, the shape a wedged native resource tends to throw.
        Throwable error = catchThrowable(() -> ServerMain.shutdownStep("erroring",
            () -> {
                ran.add("erroring");
                throw new LinkageError("native half unloaded");
            }));
        assertThat(error).as("step 2: an Error did not escape either").isNull();

        // 3. And the step after both still runs.
        ServerMain.shutdownStep("after", () -> ran.add("after"));
        assertThat(ran).as("step 3: every step ran, in order")
            .containsExactly("failing", "erroring", "after");
    }
}
