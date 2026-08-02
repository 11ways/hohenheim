package be.elevenways.hohenheim.server.docker;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The docker boot probe's two contracts: absence of the stacks role never
 * touches Docker at all, and presence with an unreachable daemon is LOUD
 * (the pre-roles behaviour was a silent per-call null).
 */
class DockerHealthTest {

    @Test
    void probeJourney() {
        // 1. Stacks role OFF: the probe answers DISABLED and the client factory
        //    is never invoked -- "no DockerClient is constructed" is the
        //    contract, not just "the probe stayed quiet".
        AtomicInteger constructions = new AtomicInteger();
        DockerHealth disabled = new DockerHealth(() -> false, () -> {
            constructions.incrementAndGet();
            return new DockerClient(new UnixSocketDockerTransport("/nonexistent/never.sock"), 500);
        });
        assertThat(disabled.probe())
            .as("step 1: a stacks-less node reports DISABLED")
            .isEqualTo(DockerHealth.Status.DISABLED);
        assertThat(constructions.get())
            .as("step 1: the disabled probe never constructed a DockerClient")
            .isZero();
        assertThat(disabled.problem())
            .as("step 1: DISABLED carries no failure detail")
            .isNull();

        // 2. Stacks role ON with an unreachable socket: UNREACHABLE, a recorded
        //    problem, and a loud structured slog naming the consequence.
        DockerHealth unreachable = new DockerHealth(() -> true, () -> {
            constructions.incrementAndGet();
            return new DockerClient(new UnixSocketDockerTransport("/nonexistent/never.sock"), 500);
        });
        String stdout = capturedStdout(() -> assertThat(unreachable.probe())
            .as("step 2: an unreachable daemon reports UNREACHABLE, not a quiet null")
            .isEqualTo(DockerHealth.Status.UNREACHABLE));
        assertThat(constructions.get())
            .as("step 2: the enabled probe DID construct a client")
            .isOne();
        assertThat(unreachable.problem())
            .as("step 2: the failure detail is recorded for the dashboard")
            .isNotBlank();
        assertThat(stdout)
            .as("step 2: the probe slogs hohenheim.docker_unreachable loudly")
            .contains("hohenheim.docker_unreachable");
    }

    private static String capturedStdout(Runnable block) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            block.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
