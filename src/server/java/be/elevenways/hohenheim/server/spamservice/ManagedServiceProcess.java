package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.zenit.common.security.KnownValueRedactor;
import be.elevenways.protoblast.server.process.RunningProcess;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.server.process.TextSink;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * Owns one long-lived service process and keeps the redacted tail of what it writes.
 *
 * AIDEV-NOTE: the process is a {@link Subprocess} the caller describes (its session, stop operator and stop grace
 * come from {@code SystemUsers.execution}); this class only adds the merged output, which the subprocess's own pump
 * decodes ({@link TextSink}) and feeds through ONE {@link KnownValueRedactor} stream per process before it lands in
 * the bounded tail, and the one stdin line, written through a pipe so its bytes can be cleared afterwards. Subprocess
 * flushes the sink once at the end of the stream, which flushes the redactor (masking a secret cut off mid-value);
 * the outcome resolves only once that happened, so a stop leaves the tail complete and redacted.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ManagedServiceProcess {

    private static final int MAX_OUTPUT_CHARS = 256 * 1024;

    private final RunningProcess process;
    private final KnownValueRedactor redactor;
    private final StringBuilder output = new StringBuilder();

    private ManagedServiceProcess(@NonNull Subprocess process, @NonNull Collection<String> secrets,
                                  @Nullable String stdinLine) throws IOException {
        this.redactor = KnownValueRedactor.of(secrets);
        if (this.redactor.refusedCount() > 0) {
            throw new IllegalArgumentException("A secret of " + process.command().get(0)
                + " is shorter than " + KnownValueRedactor.MIN_LENGTH + " characters and cannot be redacted");
        }
        try {
            this.process = process.mergeStderr()
                .stdoutTo(new RedactedOutput())
                .stdinPipe()
                .start();
        } catch (RuntimeException refused) {
            throw new IOException("Could not start " + process.command().get(0), refused);
        }
        try {
            writeStdin(stdinLine);
        } catch (IOException e) {
            this.process.stopTree();
            throw e;
        }
    }

    /** Starts a process that reads no stdin. */
    public static @NonNull ManagedServiceProcess start(@NonNull Subprocess process,
                                                        @NonNull Collection<String> secrets) throws IOException {
        return start(process, secrets, null);
    }

    /**
     * Starts a process and writes one line to its stdin before closing the pipe.
     *
     * @param secrets the exact values masked out of the output
     * @throws IOException when the process cannot be started or does not take its line
     * @throws IllegalArgumentException when a secret is too short to be redacted ({@link KnownValueRedactor#MIN_LENGTH})
     */
    public static @NonNull ManagedServiceProcess start(@NonNull Subprocess process,
                                                        @NonNull Collection<String> secrets,
                                                        @Nullable String stdinLine) throws IOException {
        return new ManagedServiceProcess(process, secrets, stdinLine);
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
        return this.process.stopTree();
    }

    public @NonNull String output() {
        synchronized (this.output) {
            return this.output.toString();
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

    private void keep(String redacted) {
        if (redacted.isEmpty()) {
            return;
        }
        synchronized (this.output) {
            this.output.append(redacted);
            int overflow = this.output.length() - MAX_OUTPUT_CHARS;
            if (overflow > 0) {
                this.output.delete(0, overflow);
            }
        }
    }

    /** The stdout sink: decoded chunks go through the redactor stream, and the end-of-stream flush ends that stream. */
    private final class RedactedOutput extends OutputStream {

        private final TextSink text = TextSink.chunks(StandardCharsets.UTF_8,
            chunk -> keep(ManagedServiceProcess.this.redactor.feed(chunk)));

        @Override
        public void write(int b) throws IOException {
            this.text.write(b);
        }

        @Override
        public void write(byte @NonNull [] bytes, int offset, int length) throws IOException {
            this.text.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            this.text.flush();
            keep(ManagedServiceProcess.this.redactor.flush());
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
