package be.elevenways.hohenheim.server.process;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * THE way the controller runs a short-lived external process to completion: stdin fed and
 * stdout and stderr drained CONCURRENTLY (stdout into a bounded buffer or streamed into a
 * sink), the deadline enforced by the wait and never by a read, and a process that overruns
 * it destroyed.
 *
 * AIDEV-NOTE: the shape every hand-rolled runner got wrong at least once. Reading a pipe to
 * its end BEFORE {@code waitFor} makes the timeout decorative (the read blocks until the
 * pipe closes, so a hung child is waited on forever), leaving stderr undrained lets a chatty
 * child fill its pipe and block, and writing a stdin body larger than the pipe buffer on the
 * calling thread wedges the caller behind a child that never reads it. Every short-lived
 * runner in the controller rides this class (nft through {@code NftRunner}, ssh-keyscan,
 * ssh-keygen, openssl, the host shell, the DNS hook, remote df, the ssh backup target, the
 * privileged helper). Deliberately NOT here: {@code GitRepository.execute}, whose overrun
 * must terminate the whole process GROUP through {@link ProcessGroupSupport#terminate}, the
 * docker dial-stdio transport, whose stdin must stay OPEN while the response is read, and
 * the long-lived followers (ssh auth watcher, managed services).
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class BoundedProcess {

    /** How long the stdin feeder may take to notice its child has gone. */
    private static final long FEED_GRACE_MILLIS = 1_000;

    /**
     * One finished (or abandoned) run.
     *
     * @param exitCode the exit status, or -1 when the run timed out or could not run at all
     * @param stdout   at most the capture cap of stdout (everything, when stderr was
     *                 redirected); empty when stdout was streamed into a sink
     * @param stderr   at most the capture cap of stderr; empty when it was redirected
     */
    public record Result(int exitCode, boolean timedOut, @NonNull String stdout,
                         @NonNull String stderr) {

        /** A run that never produced an exit status, carrying why as its stderr. */
        public static @NonNull Result notRun(@NonNull String reason) {
            return new Result(-1, false, "", reason);
        }

        /** Exited on its own with status 0. */
        public boolean succeeded() {
            return !this.timedOut && this.exitCode == 0;
        }

        /** The text a failure reports: stderr, or stdout when stderr was quiet; prefixed on a timeout. */
        public @NonNull String failureText() {
            String text = this.stderr.trim();
            if (text.isEmpty()) {
                text = this.stdout.trim();
            }
            if (!this.timedOut) {
                return text;
            }
            return text.isEmpty() ? "timed out" : "timed out: " + text;
        }
    }

    private BoundedProcess() {
    }

    /**
     * Start {@code builder}, close the child's stdin, drain its output and wait at most
     * {@code timeoutMillis} for it to exit.
     *
     * @param maxChars cap on each captured stream; the rest is read and discarded
     * @throws InterruptedIOException when the waiting thread is interrupted (the child is
     *         destroyed and the interrupt flag is restored)
     * @throws IOException when the process cannot be started
     */
    public static @NonNull Result run(@NonNull ProcessBuilder builder, long timeoutMillis,
                                      int maxChars) throws IOException {
        return run(builder, null, timeoutMillis, maxChars);
    }

    /**
     * Same, writing {@code stdin} to the child on its own thread before closing its stdin.
     *
     * @throws InterruptedIOException when the waiting thread is interrupted
     * @throws IOException when the process cannot be started
     */
    public static @NonNull Result run(@NonNull ProcessBuilder builder, @Nullable String stdin,
                                      long timeoutMillis, int maxChars) throws IOException {
        InputStream body = stdin == null
            ? null : new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        return exchange(builder, body, null, Deadline.wallClock(timeoutMillis), maxChars);
    }

    /**
     * Run {@code argv} as is (no sudo, the daemon's own environment) and never throw: a
     * command that cannot start or whose wait is interrupted is a {@link Result#notRun} result.
     */
    public static @NonNull Result execute(@NonNull List<String> argv, @Nullable String stdin,
                                          long timeoutMillis, int maxChars) {
        try {
            return run(new ProcessBuilder(argv), stdin, timeoutMillis, maxChars);
        } catch (InterruptedIOException interrupted) {
            return Result.notRun("interrupted");
        } catch (IOException failed) {
            return Result.notRun(String.valueOf(failed.getMessage()));
        }
    }

    /**
     * Run {@code builder} with {@code stdin} copied into the child and its stdout STREAMED into
     * {@code stdout}, never buffered whole, destroying the child once NO byte has moved in
     * either direction for {@code idleTimeoutMillis}.
     *
     * AIDEV-NOTE: an IDLE bound, not a wall clock, because a transfer's length is the size of
     * what it carries: a multi-gigabyte backup over a slow link must not be cut at a fixed
     * minute count, while an ssh that stopped moving bytes must still be. The bound covers
     * delivery too: after the child exits, the stdout pump keeps it while it hands its last
     * bytes to the sink, because a result returned before the sink saw them would be a
     * silently truncated stream.
     *
     * @param maxStderrChars cap on the captured stderr
     * @throws IOException when the process cannot be started, when reading {@code stdin} or
     *         writing {@code stdout} fails (the child is destroyed first), or on interruption
     */
    public static @NonNull Result stream(@NonNull ProcessBuilder builder, @Nullable InputStream stdin,
                                         @NonNull OutputStream stdout, long idleTimeoutMillis,
                                         int maxStderrChars) throws IOException {
        return exchange(builder, stdin, stdout, Deadline.idle(idleTimeoutMillis), maxStderrChars);
    }

    private static @NonNull Result exchange(@NonNull ProcessBuilder builder, @Nullable InputStream stdin,
                                            @Nullable OutputStream sink, @NonNull Deadline deadline,
                                            int maxChars) throws IOException {
        Process process = builder.start();
        ProcessGroupSupport.@Nullable OutputCapture stdout = sink == null
            ? ProcessGroupSupport.drain(process.getInputStream(),
                "bounded-process-out-" + process.pid(), maxChars)
            : null;
        @Nullable Pump pump = sink == null
            ? null : Pump.start(process, sink, deadline, "bounded-process-pump-" + process.pid());
        ProcessGroupSupport.@Nullable OutputCapture stderr = builder.redirectErrorStream()
            ? null
            : ProcessGroupSupport.drain(process.getErrorStream(),
                "bounded-process-err-" + process.pid(), maxChars);
        Feed feed = Feed.start(process, stdin, deadline, "bounded-process-in-" + process.pid());

        boolean finished;
        try {
            finished = deadline.awaitExit(process);
            if (finished && pump != null) {
                finished = pump.await();
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            settle(feed, stdout, pump, stderr);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for "
                + builder.command().get(0));
        }
        if (!finished) {
            process.destroyForcibly();
        }
        settle(feed, stdout, pump, stderr);
        if (pump != null && pump.failure() != null) {
            throw new IOException("writing the output of " + builder.command().get(0) + " failed",
                pump.failure());
        }
        if (feed.failure() != null) {
            throw new IOException("reading the input for " + builder.command().get(0) + " failed",
                feed.failure());
        }
        boolean exited = finished && !process.isAlive();
        return new Result(exited ? process.exitValue() : -1, !exited,
            stdout == null ? "" : stdout.output(), stderr == null ? "" : stderr.output());
    }

    /**
     * When a run is abandoned: a fixed wall clock, or an idle bound that every byte moved
     * through stdin or a streamed stdout pushes back.
     */
    private static final class Deadline {

        private final long budgetNanos;
        private final boolean idle;
        private volatile long anchor;

        private Deadline(long budgetMillis, boolean idle) {
            this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMillis);
            this.idle = idle;
            this.anchor = System.nanoTime();
        }

        static @NonNull Deadline wallClock(long millis) {
            return new Deadline(millis, false);
        }

        static @NonNull Deadline idle(long millis) {
            return new Deadline(millis, true);
        }

        /** Bytes moved; an idle bound starts over, a wall clock ignores it. */
        void progressed() {
            if (this.idle) {
                this.anchor = System.nanoTime();
            }
        }

        long remainingNanos() {
            return Math.max(0, this.anchor + this.budgetNanos - System.nanoTime());
        }

        /** @return true when the process exited before the bound ran out */
        boolean awaitExit(@NonNull Process process) throws InterruptedException {
            while (true) {
                long remaining = remainingNanos();
                if (remaining <= 0) {
                    return !process.isAlive();
                }
                if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    return true;
                }
            }
        }

        /** @return true when the thread ended before the bound ran out */
        boolean awaitEnd(@NonNull Thread thread) throws InterruptedException {
            while (thread.isAlive()) {
                long remaining = remainingNanos();
                if (remaining <= 0) {
                    return false;
                }
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            return true;
        }
    }

    private static void settle(@NonNull Feed feed, ProcessGroupSupport.@Nullable OutputCapture stdout,
                               @Nullable Pump pump, ProcessGroupSupport.@Nullable OutputCapture stderr) {
        feed.finish();
        if (stdout != null) {
            stdout.finish();
        }
        if (pump != null) {
            pump.finish();
        }
        if (stderr != null) {
            stderr.finish();
        }
    }

    /** Copies the stdin source into the child on its own thread, so an unread body never blocks the caller. */
    private static final class Feed {

        private final @Nullable Thread thread;
        private volatile @Nullable IOException failure;

        private Feed(@NonNull Process process, @Nullable InputStream source, @NonNull Deadline deadline,
                     @NonNull String name) {
            if (source == null) {
                this.thread = null;
                try {
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                    // a child that already exited has no stdin to close
                }
                return;
            }
            // The source stays the caller's to close; only the child's stdin is closed here.
            this.thread = Thread.ofVirtual().name(name).unstarted(() -> {
                byte[] buffer = new byte[64 * 1024];
                try (OutputStream in = process.getOutputStream()) {
                    while (true) {
                        int read;
                        try {
                            read = source.read(buffer);
                        } catch (IOException sourceFailed) {
                            // A source that fails mid-stream must not look like a short body
                            // the child accepted: stop the child and report it.
                            this.failure = sourceFailed;
                            process.destroyForcibly();
                            return;
                        }
                        if (read < 0) {
                            return;
                        }
                        in.write(buffer, 0, read);
                        deadline.progressed();
                    }
                } catch (IOException childGone) {
                    // The child exited or was destroyed before reading all of its stdin; its
                    // exit status says why.
                }
            });
            this.thread.start();
        }

        static @NonNull Feed start(@NonNull Process process, @Nullable InputStream source,
                                   @NonNull Deadline deadline, @NonNull String name) {
            return new Feed(process, source, deadline, name);
        }

        @Nullable IOException failure() {
            return this.failure;
        }

        void finish() {
            if (this.thread == null) {
                return;
            }
            boolean interrupted = Thread.interrupted();
            try {
                this.thread.join(FEED_GRACE_MILLIS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (this.thread.isAlive()) {
                this.thread.interrupt();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Streams the child's stdout into a sink on its own thread. */
    private static final class Pump {

        private final @NonNull Process process;
        private final @NonNull Deadline deadline;
        private final @NonNull Thread thread;
        private volatile @Nullable IOException failure;

        private Pump(@NonNull Process process, @NonNull OutputStream sink, @NonNull Deadline deadline,
                     @NonNull String name) {
            this.process = process;
            this.deadline = deadline;
            this.thread = Thread.ofPlatform().daemon().name(name).unstarted(() -> {
                byte[] buffer = new byte[64 * 1024];
                try (InputStream out = process.getInputStream()) {
                    int read;
                    while ((read = out.read(buffer)) >= 0) {
                        if (!deliver(sink, buffer, read)) {
                            return;
                        }
                    }
                    deliver(sink, buffer, 0);
                } catch (IOException pipeClosed) {
                    // Destroying the child closes the pipe; the exit status says why.
                }
            });
            this.thread.start();
        }

        static @NonNull Pump start(@NonNull Process process, @NonNull OutputStream sink,
                                   @NonNull Deadline deadline, @NonNull String name) {
            return new Pump(process, sink, deadline, name);
        }

        /**
         * Hand {@code length} bytes to the sink (a zero length only flushes it).
         *
         * @return false when the sink failed, after stopping the child: nobody takes the rest,
         *         so it must not block on a full pipe until the deadline
         */
        private boolean deliver(@NonNull OutputStream sink, byte @NonNull [] buffer, int length) {
            try {
                if (length > 0) {
                    sink.write(buffer, 0, length);
                    this.deadline.progressed();
                } else {
                    sink.flush();
                }
                return true;
            } catch (IOException sinkFailed) {
                this.failure = sinkFailed;
                this.process.destroyForcibly();
                return false;
            }
        }

        @Nullable IOException failure() {
            return this.failure;
        }

        /** Wait for the pump to reach end of stream; false when the deadline ran out first. */
        boolean await() throws InterruptedException {
            return this.deadline.awaitEnd(this.thread);
        }

        /** Stop a pump still held open by an escaped descendant. */
        void finish() {
            if (!this.thread.isAlive()) {
                return;
            }
            try {
                this.process.getInputStream().close();
            } catch (IOException ignored) {
                // already closed
            }
            boolean interrupted = Thread.interrupted();
            try {
                this.thread.join(500);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
