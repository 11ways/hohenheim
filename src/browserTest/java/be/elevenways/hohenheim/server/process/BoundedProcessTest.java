package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.server.security.NftRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE short-lived process runner every nft, ssh, ssh-keygen, openssl, DNS-hook and backup
 * exchange rides: its timeout is a real bound (the old runners read the pipes to EOF before
 * they ever looked at the clock), a chatty child never deadlocks on a full pipe, stdin still
 * reaches the child, and a streamed exchange neither buffers nor silently truncates.
 */
class BoundedProcessTest {

    @Test
    void theTimeoutBoundsAHungChildAndOutputNeverDeadlocks() {
        // 1. A child that hangs with its pipes open is killed at the bound, not waited on.
        long started = System.nanoTime();
        BoundedProcess.Result hung = BoundedProcess.execute(List.of("sleep", "30"), null, 1_000, 1024);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(hung.succeeded()).as("step 1: a timed-out command is a failure").isFalse();
        assertThat(hung.timedOut()).as("step 1: and says it timed out").isTrue();
        assertThat(hung.exitCode()).as("step 1: with no exit status of its own").isEqualTo(-1);
        assertThat(hung.failureText()).as("step 1: the failure text names the timeout")
            .startsWith("timed out");
        assertThat(elapsedMillis).as("step 1: returned near the 1s bound, not after 30s")
            .isLessThan(10_000);

        // 2. A child that never reads a stdin bigger than the pipe buffer cannot wedge the
        //    caller in the write either.
        String bigStdin = "x".repeat(1_048_576);
        started = System.nanoTime();
        BoundedProcess.Result unread = BoundedProcess.execute(List.of("sleep", "30"), bigStdin,
            1_000, 1024);
        elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(unread.timedOut()).as("step 2: still a timeout").isTrue();
        assertThat(elapsedMillis).as("step 2: the unread stdin did not block the caller")
            .isLessThan(10_000);

        // 3. Output larger than a pipe buffer is drained while the child runs.
        BoundedProcess.Result chatty = BoundedProcess.execute(
            List.of("head", "-c", "200000", "/dev/zero"), null, 10_000, 1_000_000);
        assertThat(chatty.succeeded()).as("step 3: a chatty child completes").isTrue();
        assertThat(chatty.stdout()).as("step 3: every byte was captured").hasSize(200_000);

        // 4. The capture cap bounds memory, never the child: the rest is read and discarded.
        BoundedProcess.Result capped = BoundedProcess.execute(
            List.of("head", "-c", "200000", "/dev/zero"), null, 10_000, 1_000);
        assertThat(capped.succeeded()).as("step 4: a capped child still completes").isTrue();
        assertThat(capped.stdout()).as("step 4: only the cap was kept").hasSize(1_000);

        // 5. Stdin reaches the child, and the exit code is the child's own.
        BoundedProcess.Result echoed = BoundedProcess.execute(List.of("cat"), "add table inet t\n",
            10_000, 1024);
        assertThat(echoed.exitCode()).as("step 5: cat exits cleanly").isZero();
        assertThat(echoed.stdout()).as("step 5: the stdin text arrived").isEqualTo("add table inet t\n");

        // 6. stderr is kept apart from stdout and is what a failure reports.
        BoundedProcess.Result failed = BoundedProcess.execute(
            List.of("sh", "-c", "echo out; echo broken >&2; exit 3"), null, 10_000, 1024);
        assertThat(failed.exitCode()).as("step 6: the child's own exit status").isEqualTo(3);
        assertThat(failed.stdout()).as("step 6: stdout alone").isEqualTo("out\n");
        assertThat(failed.failureText()).as("step 6: the failure text is stderr").isEqualTo("broken");

        // 7. A command that cannot start is a failure result, never an exception.
        BoundedProcess.Result missing = BoundedProcess.execute(
            List.of("/nonexistent/hohenheim-no-such-binary"), null, 1_000, 1024);
        assertThat(missing.exitCode()).as("step 7: an unstartable command").isEqualTo(-1);
        assertThat(missing.timedOut()).as("step 7: which is not a timeout").isFalse();
        assertThat(missing.failureText()).as("step 7: and says why").isNotBlank();
    }

    @Test
    void theNftSeamKeepsItsOwnResultShape() {
        // 1. A timeout through the nft seam is exit -1 naming the bound in seconds.
        NftRunner.Result hung = NftRunner.Result.of(List.of("sleep", "30"), null, 1);
        assertThat(hung.ok()).as("step 1: a timed-out nft call is a failure").isFalse();
        assertThat(hung.failureText()).as("step 1: naming the bound").contains("timed out after 1s");

        // 2. A ruleset on stdin reaches the child and its stdout comes back whole.
        NftRunner.Result echoed = NftRunner.Result.of(List.of("cat"), "add table inet t\n", 10);
        assertThat(echoed.ok()).as("step 2: the call succeeded").isTrue();
        assertThat(echoed.stdout()).as("step 2: the ruleset text arrived").isEqualTo("add table inet t\n");
    }

    @Test
    void aStreamedExchangeDeliversEveryByteAndRefusesATruncatedOne() throws IOException {
        // 1. stdin streams in and stdout streams out, far beyond any capture cap.
        byte[] body = "0123456789".repeat(100_000).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BoundedProcess.Result copied = BoundedProcess.stream(new ProcessBuilder("cat"),
            new ByteArrayInputStream(body), sink, 10_000, 1024);
        assertThat(copied.succeeded()).as("step 1: the streamed copy completed").isTrue();
        assertThat(sink.toByteArray()).as("step 1: every byte reached the sink").isEqualTo(body);
        assertThat(copied.stdout()).as("step 1: nothing was buffered in the result").isEmpty();

        // 2. A sink that fails stops the child and is reported, never a short success.
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("disk full");
            }
        };
        long started = System.nanoTime();
        assertThatThrownBy(() -> BoundedProcess.stream(new ProcessBuilder("cat", "/dev/zero"),
            null, broken, 30_000, 1024))
            .as("step 2: the sink failure surfaces")
            .isInstanceOf(IOException.class)
            .hasRootCauseMessage("disk full");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
            .as("step 2: the endless child was stopped, not waited on until the deadline")
            .isLessThan(10_000);

        // 3. A stdin source that fails mid-stream is reported, never a short body accepted.
        InputStream failing = new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (this.served++ < 1_000) {
                    return 'x';
                }
                throw new IOException("producer died");
            }
        };
        assertThatThrownBy(() -> BoundedProcess.stream(new ProcessBuilder("cat"), failing,
            new ByteArrayOutputStream(), 10_000, 1024))
            .as("step 3: the source failure surfaces")
            .isInstanceOf(IOException.class)
            .hasRootCauseMessage("producer died");

        // 4. The streamed bound is IDLE time: a transfer that keeps moving outlives it...
        ByteArrayOutputStream ticks = new ByteArrayOutputStream();
        BoundedProcess.Result moving = BoundedProcess.stream(new ProcessBuilder("sh", "-c",
            "for i in 1 2 3 4; do echo $i; sleep 0.6; done"), null, ticks, 1_500, 1024);
        assertThat(moving.succeeded()).as("step 4: 2.4s of steady output beat a 1.5s idle bound")
            .isTrue();
        assertThat(ticks.toString(StandardCharsets.UTF_8)).as("step 4: every tick arrived")
            .isEqualTo("1\n2\n3\n4\n");

        // 5. ...and one that stops moving is cut at it.
        long started5 = System.nanoTime();
        BoundedProcess.Result stalled = BoundedProcess.stream(new ProcessBuilder("sleep", "30"),
            null, new ByteArrayOutputStream(), 1_000, 1024);
        assertThat(stalled.timedOut()).as("step 5: a silent child is abandoned").isTrue();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started5))
            .as("step 5: near the idle bound, not after 30s").isLessThan(10_000);
    }
}
