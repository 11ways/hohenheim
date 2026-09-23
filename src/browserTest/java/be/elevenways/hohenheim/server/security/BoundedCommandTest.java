package be.elevenways.hohenheim.server.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The external-command runner every nft, ssh and ssh-keygen call rides: its timeout is a real
 * bound (the old runner read the pipes to EOF before it ever looked at the clock), a chatty
 * child never deadlocks on a full pipe, and stdin still reaches the child.
 */
class BoundedCommandTest {

    @Test
    void theTimeoutBoundsAHungChildAndOutputNeverDeadlocks() {
        // 1. A child that hangs with its pipes open is killed at the bound, not waited on.
        long started = System.nanoTime();
        NftRunner.Result hung = BoundedCommand.run(List.of("sleep", "30"), null, 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(hung.ok()).as("step 1: a timed-out command is a failure").isFalse();
        assertThat(hung.stderr()).as("step 1: and says why").contains("timed out after 1s");
        assertThat(elapsedMillis).as("step 1: returned near the 1s bound, not after 30s")
            .isLessThan(10_000);

        // 2. A child that never reads a stdin bigger than the pipe buffer cannot wedge the
        //    caller in the write either.
        String bigStdin = "x".repeat(1_048_576);
        started = System.nanoTime();
        NftRunner.Result unread = BoundedCommand.run(List.of("sleep", "30"), bigStdin, 1);
        elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(unread.ok()).as("step 2: still a timeout").isFalse();
        assertThat(elapsedMillis).as("step 2: the unread stdin did not block the caller")
            .isLessThan(10_000);

        // 3. Output larger than a pipe buffer is drained while the child runs.
        NftRunner.Result chatty = BoundedCommand.run(
            List.of("head", "-c", "200000", "/dev/zero"), null, 10);
        assertThat(chatty.ok()).as("step 3: a chatty child completes").isTrue();
        assertThat(chatty.stdout()).as("step 3: every byte was captured").hasSize(200_000);

        // 4. Stdin reaches the child, and the exit code is the child's own.
        NftRunner.Result echoed = BoundedCommand.run(List.of("cat"), "add table inet t\n", 10);
        assertThat(echoed.exitCode()).as("step 4: cat exits cleanly").isZero();
        assertThat(echoed.stdout()).as("step 4: the ruleset text arrived").isEqualTo("add table inet t\n");

        // 5. A command that cannot start is a failure result, never an exception.
        NftRunner.Result missing = BoundedCommand.run(
            List.of("/nonexistent/hohenheim-no-such-binary"), null, 1);
        assertThat(missing.exitCode()).as("step 5: an unstartable command").isEqualTo(-1);
    }
}
