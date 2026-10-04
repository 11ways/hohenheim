package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.RunningProcess;
import be.elevenways.protoblast.server.process.Subprocess;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/**
 * Owns one long-lived service process and keeps the redacted tail of what it writes.
 *
 * AIDEV-NOTE: the process is a {@link Subprocess} the caller describes (its session, stop operator and stop grace
 * come from {@code SystemUsers.execution}); this class only adds the merged output, which a platform thread reads
 * into a bounded tail, and the one stdin line, written through a pipe so its bytes can be cleared afterwards.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ManagedServiceProcess {

    private static final int MAX_OUTPUT_CHARS = 256 * 1024;
    private static final int REDACTION_OVERLAP_CHARS = 1_024;

    private final RunningProcess process;
    private final UnaryOperator<String> redactor;
    private final StringBuilder output = new StringBuilder();
    private final Thread outputPump;

    private ManagedServiceProcess(@NonNull RunningProcess process, @NonNull UnaryOperator<String> redactor,
                                  @Nullable String stdinLine) throws IOException {
        this.process = process;
        this.redactor = redactor;
        this.outputPump = Thread.ofPlatform().daemon().name("managed-service-" + process.pid() + "-output")
            .start(() -> pump(process.stdout()));
        try {
            writeStdin(stdinLine);
        } catch (IOException e) {
            this.process.stopTree();
            awaitPump();
            throw e;
        }
    }

    /** Starts a process that reads no stdin. */
    public static @NonNull ManagedServiceProcess start(@NonNull Subprocess process,
                                                        @NonNull UnaryOperator<String> redactor) throws IOException {
        return start(process, redactor, null);
    }

    /**
     * Starts a process and writes one line to its stdin before closing the pipe.
     *
     * @throws IOException when the process cannot be started or does not take its line
     */
    public static @NonNull ManagedServiceProcess start(@NonNull Subprocess process,
                                                        @NonNull UnaryOperator<String> redactor,
                                                        @Nullable String stdinLine) throws IOException {
        RunningProcess running;
        try {
            running = process.mergeStderr().streamStdout().stdinPipe().start();
        } catch (RuntimeException refused) {
            throw new IOException("Could not start " + process.command().get(0), refused);
        }
        return new ManagedServiceProcess(running, redactor, stdinLine);
    }

    public long pid() {
        return this.process.pid();
    }

    public boolean isAlive() {
        return this.process.isAlive();
    }

    /** Runs {@code action} once the process ended and its output was drained. */
    public void onExit(@NonNull Runnable action) {
        this.process.completion().whenDone((outcome, failure) -> action.run());
    }

    /** Asks the whole session to end and returns at once; {@link #stop()} waits for it. */
    public void terminate() {
        this.process.stop();
    }

    /** @return true when the process ended within the wait */
    public boolean waitFor(long timeoutMs) throws InterruptedException {
        try {
            this.process.await(Duration.ofMillis(timeoutMs));
            return true;
        } catch (TimeoutException stillRunning) {
            return false;
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            throw failed;
        }
    }

    /** @return the exit code of a process that ended */
    public int exitValue() {
        ProcessOutcome outcome = this.process.await();
        return outcome.exitCode();
    }

    /**
     * Stops the complete session, also what it holds after its leader exited, and drains the output.
     *
     * @return true when nothing of the session is known to run afterwards
     */
    public synchronized boolean stop() {
        boolean ended = this.process.stopTree();
        awaitPump();
        return ended;
    }

    public @NonNull String output() {
        synchronized (this.output) {
            String redacted = this.redactor.apply(this.output.toString());
            return redacted.length() <= MAX_OUTPUT_CHARS
                ? redacted : redacted.substring(redacted.length() - MAX_OUTPUT_CHARS);
        }
    }

    private void writeStdin(@Nullable String line) throws IOException {
        byte[] bytes = line == null ? new byte[0] : (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (OutputStream input = this.process.stdin()) {
            if (bytes.length > 0) {
                input.write(bytes);
                input.flush();
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void pump(InputStream input) {
        byte[] buffer = new byte[8_192];
        try (input) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // The stream closing during session cleanup is normal.
        }
    }

    private void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (this.output) {
            this.output.append(text);
            int overflow = this.output.length() - (MAX_OUTPUT_CHARS + REDACTION_OVERLAP_CHARS);
            if (overflow > 0) {
                this.output.delete(0, overflow);
            }
        }
    }

    private void awaitPump() {
        boolean interrupted = Thread.interrupted();
        try {
            this.outputPump.join(2_000);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
