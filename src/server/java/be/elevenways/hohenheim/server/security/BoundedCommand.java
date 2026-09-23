package be.elevenways.hohenheim.server.security;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one short-lived external command (nft, ssh, ssh-keygen, openssl) with an optional stdin
 * body and a HARD wall-clock bound, capturing stdout and stderr.
 *
 * AIDEV-NOTE: the bound is the whole point. The runner this replaced read both pipes to EOF
 * before calling {@code waitFor(timeout)}, so a child that hung with its pipes open (sudo waiting
 * for a password, ssh stuck in a handshake, a wedged nft) blocked the caller forever and the
 * timeout never ran. Here the stdin writer and both pipe drainers run on their own threads from
 * the start (a full pipe buffer can still never deadlock the child), the caller waits on the
 * PROCESS with the timeout, and a timed-out child is destroyed, which closes the pipes and ends
 * the helpers. A grandchild that inherited the pipes can keep them open after the child exits
 * (an ssh ControlMaster), so the drainers are joined with a short grace and whatever they
 * captured is returned.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class BoundedCommand {

    /** How long captured output may keep arriving after the process itself has ended. */
    private static final long DRAIN_GRACE_MILLIS = 1_000;

    private BoundedCommand() {
    }

    /**
     * @param argv           the full command line
     * @param stdin          text written to the child's stdin, or null to close it at once
     * @param timeoutSeconds the wall-clock bound on the whole command
     * @return the exit code (-1 on timeout, start failure or interruption) and captured text
     */
    public static NftRunner.@NonNull Result run(@NonNull List<String> argv, @Nullable String stdin,
                                                long timeoutSeconds) {
        Process process;
        try {
            process = new ProcessBuilder(argv).start();
        } catch (IOException e) {
            return new NftRunner.Result(-1, "", String.valueOf(e.getMessage()));
        }

        Drain out = Drain.start(process.getInputStream(), "stdout");
        Drain err = Drain.start(process.getErrorStream(), "stderr");
        Thread writer = Thread.ofVirtual().name("bounded-command-stdin").start(() -> {
            try (OutputStream in = process.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException closed) {
                // The child exited or was destroyed before reading its stdin; its exit says why.
            }
        });

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                writer.interrupt();
                return new NftRunner.Result(-1, out.text(), "timed out after " + timeoutSeconds + "s");
            }
            return new NftRunner.Result(process.exitValue(), out.text(), err.text());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new NftRunner.Result(-1, out.text(), "interrupted");
        }
    }

    /** One pipe drained to memory on its own virtual thread. */
    private static final class Drain {

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final Thread thread;

        private Drain(@NonNull InputStream source, @NonNull String name) {
            this.thread = Thread.ofVirtual().name("bounded-command-" + name).unstarted(() -> {
                byte[] buffer = new byte[8192];
                try (source) {
                    int read;
                    while ((read = source.read(buffer)) >= 0) {
                        synchronized (this.captured) {
                            this.captured.write(buffer, 0, read);
                        }
                    }
                } catch (IOException closed) {
                    // Destroying the child closes the pipe; what was read so far is the answer.
                }
            });
        }

        static @NonNull Drain start(@NonNull InputStream source, @NonNull String name) {
            Drain drain = new Drain(source, name);
            drain.thread.start();
            return drain;
        }

        /** The captured text, after giving the drainer a short grace to reach end of stream. */
        @NonNull String text() {
            try {
                this.thread.join(DRAIN_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (this.captured) {
                return this.captured.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
