package be.elevenways.hohenheim.server.process;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

/**
 * THE way the controller runs a short-lived helper process to completion: stdout and stderr
 * drained CONCURRENTLY into bounded buffers, the deadline enforced by the wait and never by
 * a read, and a process that overruns it destroyed.
 *
 * AIDEV-NOTE: the shape every hand-rolled runner got wrong at least once. Reading a pipe to
 * its end BEFORE {@code waitFor} makes the timeout decorative (the read blocks until the
 * pipe closes, so a hung child is waited on forever), and leaving stderr undrained lets a
 * chatty child fill its pipe and block. {@code HostShell.run} fixed the first by hand; this
 * is the one helper so the next runner does not have to. Known copies still hand-rolled
 * elsewhere: {@code NftRunner}, {@code SpamserviceManager.ensureOwned},
 * {@code RestoreCapacity.remoteAvailable}, {@code HostShell}.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class BoundedProcess {

    /**
     * One finished (or abandoned) run.
     *
     * @param exitCode the exit status, or -1 when the run timed out
     * @param stdout   at most the capture cap of stdout (everything, when stderr was redirected)
     * @param stderr   at most the capture cap of stderr; empty when it was redirected
     */
    public record Result(int exitCode, boolean timedOut, @NonNull String stdout,
                         @NonNull String stderr) {

        /** Exited on its own with status 0. */
        public boolean succeeded() {
            return !this.timedOut && this.exitCode == 0;
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
        Process process = builder.start();
        ProcessGroupSupport.OutputCapture stdout = ProcessGroupSupport.drain(
            process.getInputStream(), "bounded-process-out-" + process.pid(), maxChars);
        ProcessGroupSupport.@Nullable OutputCapture stderr = builder.redirectErrorStream()
            ? null
            : ProcessGroupSupport.drain(process.getErrorStream(),
                "bounded-process-err-" + process.pid(), maxChars);
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // a child that already exited has no stdin to close
        }
        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            finish(stdout, stderr);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for "
                + builder.command().get(0));
        }
        if (!finished) {
            process.destroyForcibly();
        }
        finish(stdout, stderr);
        return new Result(finished ? process.exitValue() : -1, !finished, stdout.output(),
            stderr == null ? "" : stderr.output());
    }

    private static void finish(ProcessGroupSupport.@NonNull OutputCapture stdout,
                               ProcessGroupSupport.@Nullable OutputCapture stderr) {
        stdout.finish();
        if (stderr != null) {
            stderr.finish();
        }
    }
}
