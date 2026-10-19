package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.server.process.Termination;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PaaS CLI's own smoke suite ({@code tools/hoh.test.js}), run as part of the
 * verification lane instead of by hand.
 *
 * AIDEV-NOTE: it lived in the repo unwired -- no Gradle task and no lane referenced it,
 * so the CLI's tests had never executed in any run (2026-08-09; they were green, which is
 * luck, not evidence). A JUnit wrapper rather than a bare Exec task ON PURPOSE: zenit-dev
 * drives the `browserTest` / `browserTestIsolated` tasks and nothing else, so an Exec task
 * hanging off `check` would have been unwired a second time. This class is untagged, so it
 * rides the isolated bucket. Node is a hard requirement of the whole build lane (zenit-dev
 * IS a node CLI), so an absent runtime is a real failure and never a skip.
 */
class HohCliTest {

    @Test
    void thePaasCliSmokeSuitePasses() {
        Path suite = Path.of("tools/hoh.test.js");
        assertThat(Files.isRegularFile(suite))
            .as("the CLI suite is where the wiring expects it (working dir = project root)")
            .isTrue();

        ProcessOutcome outcome = Subprocess.of("node", suite.toString())
            .mergeStderr()
            .timeout(Duration.ofMinutes(2))
            .run();
        String output = outcome.stdout().text();
        assertThat(outcome.termination()).as("the CLI suite terminated:\n" + output).isEqualTo(Termination.EXITED);

        // Both halves matter: the exit code is the verdict, and the banner proves the
        // suite RAN to the end rather than exiting 0 after asserting nothing.
        assertThat(outcome.exitCode())
            .as("node tools/hoh.test.js exited cleanly:\n" + output)
            .isEqualTo(0);
        assertThat(output)
            .as("the CLI suite reached its own final verdict:\n" + output)
            .contains("ALL PASSED");
    }
}
