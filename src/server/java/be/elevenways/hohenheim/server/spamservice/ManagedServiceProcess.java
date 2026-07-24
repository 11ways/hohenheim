package be.elevenways.hohenheim.server.spamservice;

import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.process.ProcessGroupSupport;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/** Owns one process group and drains its output on dedicated platform threads. */
public final class ManagedServiceProcess {

    private static final int MAX_OUTPUT_CHARS = 256 * 1024;
    private static final int REDACTION_OVERLAP_CHARS = 1_024;

    private final Process process;
    private final SystemUsers.@Nullable RunAsUser runAs;
    private final ProcessGroupSupport.@Nullable Operator processGroupOperator;
    private final UnaryOperator<String> redactor;
    private final StringBuilder output = new StringBuilder();
    private final List<InputStream> outputInputs;
    private final List<Thread> outputPumps;

    private ManagedServiceProcess(@NonNull Process process,
                                  SystemUsers.@Nullable RunAsUser runAs,
                                  @NonNull UnaryOperator<String> redactor,
                                  ProcessGroupSupport.@Nullable Operator processGroupOperator,
                                  @Nullable String stdinLine) throws IOException {
        this.process = process;
        this.runAs = runAs;
        this.redactor = redactor;
        this.processGroupOperator = processGroupOperator;
        this.outputInputs = List.of(process.getInputStream(), process.getErrorStream());
        this.outputPumps = List.of(
            startPump(this.outputInputs.get(0), "managed-service-" + process.pid() + "-stdout"),
            startPump(this.outputInputs.get(1), "managed-service-" + process.pid() + "-stderr"));
        try {
            writeStdin(stdinLine);
        } catch (IOException e) {
            if (this.processGroupOperator == null) {
                ProcessGroupSupport.terminate(this.process, this.runAs, 100);
            } else {
                ProcessGroupSupport.terminate(this.process, this.runAs, 100, this.processGroupOperator);
            }
            awaitPumps();
            throw e;
        }
    }

    /** Starts a process whose builder already has an explicit environment and working directory. */
    public static @NonNull ManagedServiceProcess start(@NonNull ProcessBuilder builder,
                                                        SystemUsers.@Nullable RunAsUser runAs,
                                                        @NonNull UnaryOperator<String> redactor)
            throws IOException {
        builder.redirectErrorStream(false);
        return new ManagedServiceProcess(builder.start(), runAs, redactor, null, null);
    }

    /** Starts a process and writes one line to its stdin before closing the pipe. */
    public static @NonNull ManagedServiceProcess start(@NonNull ProcessBuilder builder,
                                                        SystemUsers.@Nullable RunAsUser runAs,
                                                        @NonNull UnaryOperator<String> redactor,
                                                        @Nullable String stdinLine)
            throws IOException {
        builder.redirectErrorStream(false);
        return new ManagedServiceProcess(builder.start(), runAs, redactor, null, stdinLine);
    }

    /** Test seam for process-group signaling. */
    static @NonNull ManagedServiceProcess start(@NonNull ProcessBuilder builder,
                                                 SystemUsers.@Nullable RunAsUser runAs,
                                                 @NonNull UnaryOperator<String> redactor,
                                                 ProcessGroupSupport.@NonNull Operator operator)
            throws IOException {
        return start(builder, runAs, redactor, operator, null);
    }

    /** Test seam for process-group signaling plus one-line stdin delivery. */
    static @NonNull ManagedServiceProcess start(@NonNull ProcessBuilder builder,
                                                 SystemUsers.@Nullable RunAsUser runAs,
                                                 @NonNull UnaryOperator<String> redactor,
                                                 ProcessGroupSupport.@NonNull Operator operator,
                                                 @Nullable String stdinLine)
            throws IOException {
        builder.redirectErrorStream(false);
        return new ManagedServiceProcess(builder.start(), runAs, redactor, operator, stdinLine);
    }

    public long pid() {
        return this.process.pid();
    }

    public boolean isAlive() {
        return this.process.isAlive();
    }

    public @NonNull Process process() {
        return this.process;
    }

    public boolean waitFor(long timeoutMs) throws InterruptedException {
        return this.process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public int exitValue() {
        return this.process.exitValue();
    }

    /** Terminates the complete setsid process group and drains both output pipes. */
    public synchronized boolean stop(long gracefulWaitMs) {
        ProcessGroupSupport.TerminationResult result = this.processGroupOperator == null
            ? ProcessGroupSupport.terminate(this.process, this.runAs, gracefulWaitMs)
            : ProcessGroupSupport.terminate(this.process, this.runAs, gracefulWaitMs,
                this.processGroupOperator);
        awaitPumps();
        return result.successful();
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
        try (var input = this.process.getOutputStream()) {
            if (bytes.length > 0) {
                input.write(bytes);
                input.flush();
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private Thread startPump(InputStream input, String name) {
        return Thread.ofPlatform().daemon().name(name).start(() -> pump(input));
    }

    private void pump(InputStream input) {
        byte[] buffer = new byte[8_192];
        try (input) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // Pipe closure during process-group cleanup is normal.
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

    private void awaitPumps() {
        boolean interrupted = Thread.interrupted();
        for (int i = 0; i < this.outputPumps.size(); i++) {
            Thread pump = this.outputPumps.get(i);
            try {
                pump.join(2_000);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (pump.isAlive()) {
                try {
                    this.outputInputs.get(i).close();
                    pump.join(500);
                } catch (IOException ignored) {
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
