package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.process.BoundedProcess;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
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

            List<String> argv = this.server != null && ServerModel.hasSshLane(this.server)
                ? sshArgv(this.server, script)
                : List.of("sh", "-c", script);

            if (argv.isEmpty()) {
                return new Result(1, "no ssh lane could be built for this host");
            }

            // AIDEV-NOTE: BoundedProcess drains the output on its own thread and enforces
            // the deadline with the wait, never a read. Reading inline made the timeout
            // decorative -- readAllBytes blocks until the pipe closes, so a snippet that never
            // finished was waited on forever; a runtime-image build runs for minutes through
            // here, which is where that would have shown up as a thread nobody can free.
            try {
                BoundedProcess.Result result = BoundedProcess.run(
                    new ProcessBuilder(argv).redirectErrorStream(true),
                    TimeUnit.SECONDS.toMillis(timeoutSeconds), MAX_OUTPUT_CHARS);
                if (result.timedOut()) {
                    return new Result(1, "the host command timed out");
                }
                return new Result(result.exitCode(), result.stdout().trim());
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
    }
}
