package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.server.process.ProcessOutcome;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.server.process.Termination;
import be.elevenways.zenit.common.orm.datasource.Row;
import java.time.Duration;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * THE way this controller runs a shell snippet on a host it manages: locally for a local
 * host, over the pinned ssh lane for an ssh one.
 *
 * AIDEV-NOTE: this is a SEAM, not a convenience. Everything that has to read or change the
 * host filesystem (the volume-backend probe, the btrfs volume operations) goes through it,
 * which is what lets a unit test hand those mechanisms a fake shell and assert on the
 * COMMANDS instead of needing a real kernel. A second hand-rolled Subprocess beside it
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

    /** How long one payload transfer (a volume's contents) may take. */
    long PAYLOAD_TIMEOUT_SECONDS = 3600;

    /**
     * Run one snippet whose stdout IS a binary payload, written to {@code target}; the result's text is stderr.
     *
     * AIDEV-NOTE: a default that refuses, so a test's lambda shell (scripts and text only) stays a lambda; the
     * process shell is the one that streams.
     *
     * @param maxBytes cap on the payload, enforced while it arrives
     */
    default @NonNull Result runReading(@NonNull String script, @NonNull Path target, long maxBytes,
                                       long timeoutSeconds) {
        return new Result(1, "this shell cannot stream a payload");
    }

    /** Run one snippet with {@code source} as its stdin; the result's text is stdout plus stderr. */
    default @NonNull Result runFeeding(@NonNull String script, @NonNull Path source, long timeoutSeconds) {
        return new Result(1, "this shell cannot stream a payload");
    }

    /**
     * Whether snippets already run as root on the host, so a privileged command needs no
     * {@code sudo} in front of it.
     *
     * AIDEV-NOTE: the controller runs UNPRIVILEGED by design (deploy-native.md: narrow
     * sudoers grants, never a root service), and the local lane is a plain
     * {@code sh -c} as the service user. The btrfs volume lane, however, is root work by
     * nature -- chown to a workspace's foreign uid, qgroup limits, subvolume delete and
     * snapshot all refuse to an ordinary user -- so a consumer that must act as root asks
     * this and goes through {@link PrivilegedHelper} (legacy: {@code sudo -n} per binary,
     * see {@code BtrfsVolumeOperations.privileged}).
     * A denied {@code sudo -n} fails loudly with sudo's own text, never silently, which
     * is what turned the starfleet {@code volume_own_failed} into a named sudoers gap.
     * The ssh lane counts as root only when the pinned target logs in as root.
     */
    default boolean elevated() {
        return true;
    }

    /** The {@code sudo -n } prefix a privileged command needs on this shell, or nothing. */
    default @NonNull String sudo() {
        return this.elevated() ? "" : "sudo -n ";
    }

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

        /** Cap on the captured output of one snippet; the rest is read and discarded. */
        private static final int MAX_OUTPUT_CHARS = 1024 * 1024;

        private final @Nullable Row server;

        ProcessHostShell(@Nullable Row server) {
            this.server = server;
        }

        @Override
        public boolean elevated() {
            if (this.server != null && ServerModel.hasSshLane(this.server)) {
                String target = String.valueOf((Object) this.server.get(ServerModel.SSH_TARGET));
                return target.startsWith("root@");
            }
            return SystemUsers.daemonRunsAsRoot();
        }

        @Override
        public @NonNull Result run(@NonNull String script, long timeoutSeconds) {
            List<String> argv = argvFor(script);
            if (argv.isEmpty()) {
                return new Result(1, "no ssh lane could be built for this host");
            }

            // AIDEV-NOTE: Subprocess drains the output on its own thread and enforces
            // the deadline with the wait, never a read. Reading inline made the timeout
            // decorative -- readAllBytes blocks until the pipe closes, so a snippet that never
            // finished was waited on forever; a runtime-image build runs for minutes through
            // here, which is where that would have shown up as a thread nobody can free.
            return outcomeOf(Subprocess.of(argv)
                .mergeStderr()
                .collectStdout(MAX_OUTPUT_CHARS), timeoutSeconds, false);
        }

        @Override
        public @NonNull Result runReading(@NonNull String script, @NonNull Path target, long maxBytes,
                                          long timeoutSeconds) {
            List<String> argv = argvFor(script);
            if (argv.isEmpty()) {
                return new Result(1, "no ssh lane could be built for this host");
            }
            try (OutputStream sink = new CappedSink(Files.newOutputStream(target), maxBytes)) {
                return outcomeOf(Subprocess.of(argv)
                    .stdoutTo(sink)
                    .stderrLimit(MAX_OUTPUT_CHARS), timeoutSeconds, true);
            } catch (IOException failed) {
                return new Result(1, String.valueOf(failed.getMessage()));
            }
        }

        @Override
        public @NonNull Result runFeeding(@NonNull String script, @NonNull Path source, long timeoutSeconds) {
            List<String> argv = argvFor(script);
            if (argv.isEmpty()) {
                return new Result(1, "no ssh lane could be built for this host");
            }
            try (InputStream input = Files.newInputStream(source)) {
                return outcomeOf(Subprocess.of(argv)
                    .stdin(input)
                    .mergeStderr()
                    .collectStdout(MAX_OUTPUT_CHARS), timeoutSeconds, false);
            } catch (IOException failed) {
                return new Result(1, String.valueOf(failed.getMessage()));
            }
        }

        private @NonNull List<String> argvFor(@NonNull String script) {
            return this.server != null && ServerModel.hasSshLane(this.server)
                ? sshArgv(this.server, script)
                : List.of("sh", "-c", script);
        }

        /** @param stderrText whether the result's text is stderr (stdout carried a payload) */
        private static @NonNull Result outcomeOf(@NonNull Subprocess process, long timeoutSeconds,
                                                 boolean stderrText) {
            try {
                ProcessOutcome result = process
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .stopGrace(Duration.ZERO)
                    .runChecked();
                if (result.termination() == Termination.TIMED_OUT) {
                    return new Result(1, "the host command timed out");
                }
                if (result.failure() != null) {
                    return new Result(1, String.valueOf(result.failure().getMessage()));
                }
                return new Result(result.exitCode(),
                    (stderrText ? result.stderr() : result.stdout()).text().trim());
            } catch (InterruptedIOException interrupted) {
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

        /** A payload sink that fails the transfer once it would pass its cap. */
        private static final class CappedSink extends FilterOutputStream {

            private final long maxBytes;
            private long written;

            CappedSink(@NonNull OutputStream target, long maxBytes) {
                super(target);
                this.maxBytes = maxBytes;
            }

            @Override
            public void write(int b) throws IOException {
                count(1);
                this.out.write(b);
            }

            @Override
            public void write(byte @NonNull [] bytes, int offset, int length) throws IOException {
                count(length);
                this.out.write(bytes, offset, length);
            }

            private void count(int length) throws IOException {
                this.written += length;
                if (this.written > this.maxBytes) {
                    throw new IOException("the payload passed its cap of " + this.maxBytes + " bytes");
                }
            }
        }
    }
}
