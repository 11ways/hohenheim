package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

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
    void thePaasCliSmokeSuitePasses() throws IOException, InterruptedException {
        Path suite = Path.of("tools/hoh.test.js");
        assertThat(Files.isRegularFile(suite))
            .as("the CLI suite is where the wiring expects it (working dir = project root)")
            .isTrue();

        Process process = new ProcessBuilder("node", suite.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(2, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).as("the CLI suite terminated:\n" + output).isTrue();

        // Both halves matter: the exit code is the verdict, and the banner proves the
        // suite RAN to the end rather than exiting 0 after asserting nothing.
        assertThat(process.exitValue())
            .as("node tools/hoh.test.js exited cleanly:\n" + output)
            .isEqualTo(0);
        assertThat(output)
            .as("the CLI suite reached its own final verdict:\n" + output)
            .contains("ALL PASSED");
    }
}
