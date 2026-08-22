package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * THE way this controller runs a shell snippet on a host it manages: locally for a local
 * host, over the pinned ssh lane for an ssh one.
 *
 * AIDEV-NOTE: this is a SEAM, not a convenience. Everything that has to read or change the
 * host filesystem (the volume-backend probe, the btrfs volume operations) goes through it,
 * which is what lets a unit test hand those mechanisms a fake shell and assert on the
 * COMMANDS instead of needing a real kernel. A second hand-rolled ProcessBuilder beside it
 * would put half of that behaviour outside every test that matters.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public interface HostShell {

    /** How long a single snippet may take before the host counts as unanswerable. */
    long TIMEOUT_SECONDS = 20;

    /** One command's outcome; the text is stdout plus stderr, whichever spoke. */
    record Result(int exitCode, @NonNull String text) {

        public boolean ok() {
            return this.exitCode == 0;
        }
    }

    /** Run one shell snippet and report its outcome; never throws for a failing command. */
    default @NonNull Result run(@NonNull String script) {
        return run(script, TIMEOUT_SECONDS);
    }

    /**
     * Run one shell snippet under an explicit wall-clock cap.
     *
     * AIDEV-NOTE: the cap is an ARGUMENT because building a runtime image on a host takes
     * minutes while a filesystem probe must never hang a preflight for more than seconds.
     * One constant could only be wrong for one of them, and the direction it would be
     * wrong in (a probe that blocks boot) is the worse one.
     */
    @NonNull Result run(@NonNull String script, long timeoutSeconds);

    /** The shell for an inventoried host record. */
    static @NonNull HostShell forServer(@NonNull Row server) {
        return new ProcessHostShell(server);
    }

    /** The shell of the controller's own machine. */
    static @NonNull HostShell local() {
        return new ProcessHostShell(null);
    }

    /** POSIX single-quoting: the only quoting that survives an arbitrary path. */
    static @NonNull String quote(@Nullable String value) {
        String text = value == null ? "" : value;
        return "'" + text.replace("'", "'\\''") + "'";
    }

    /** The process-backed implementation; local argv or the pinned ssh argv. */
    final class ProcessHostShell implements HostShell {

        private final @Nullable Row server;

        ProcessHostShell(@Nullable Row server) {
            this.server = server;
        }

        @Override
        public @NonNull Result run(@NonNull String script, long timeoutSeconds) {

            List<String> argv = this.server != null && ServerModel.hasSshLane(this.server)
                ? sshArgv(this.server, script)
                : List.of("sh", "-c", script);

            if (argv.isEmpty()) {
                return new Result(1, "no ssh lane could be built for this host");
            }

            try {
                Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
                // AIDEV-NOTE: the output is drained on its OWN thread. Reading it inline
                // made the timeout decorative -- readAllBytes blocks until the pipe closes,
                // so a snippet that never finishes was waited on forever and the
                // waitFor(...) below was only ever reached by a process that had already
                // exited. A runtime-image build runs for minutes through here, which is
                // where that would have shown up as a controller thread nobody can free.
                StringBuilder collected = new StringBuilder();
                Thread drain = new Thread(() -> {
                    try {
                        collected.append(new String(process.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8));
                    } catch (IOException closed) {
                        // the stream died with the process; whatever arrived is the output
                    }
                }, "host-shell-output");
                drain.setDaemon(true);
                drain.start();
                try {
                    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        return new Result(1, "the host command timed out");
                    }
                    drain.join(TimeUnit.SECONDS.toMillis(2));
                    return new Result(process.exitValue(),
                        collected.toString().trim());
                } finally {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Result(1, "the host command was interrupted");
            } catch (IOException failed) {
                return new Result(1, String.valueOf(failed.getMessage()));
            }
        }

        private static @NonNull List<String> sshArgv(@NonNull Row server,
                                                     @NonNull String script) {
            try {
                return HostKeys.sshArgv(server, List.of("sh", "-c", quote(script)));
            } catch (RuntimeException refusal) {
                // An unpinned host cannot be reached at all; that refusal is the verdict.
                Blast.log("HostShell: cannot reach host", server.get(ServerModel.NAME),
                    "-", refusal.getMessage());
                return List.of();
            }
        }
    }
}
