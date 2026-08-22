package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The ARBITRARY-command half of the driver seam: run an operator-supplied command inside
 * a running workload and report its exit code and bounded output. A driver without it does
 * not implement this, and exec refuses with a named violation.
 *
 * AIDEV-NOTE: deliberately SINGLE-SHOT, like every other method on {@link InstanceRuntime}.
 * The interactive TTY exec the plan sketches is a STREAMING contract and is not this: it
 * would need the second transport, and bolting a stream onto this method is exactly what
 * InstanceRuntime's docblock forbids. This is not a console either -- the console reaches
 * the workload's OWN primary process over stdin, this starts a new program, which is why
 * the two answer to different capabilities.
 */
public interface ExecSupport {

    /** One exec run: the process exit code plus its combined, bounded output. */
    record ExecOutcome(int exitCode, @NonNull String outputTail) {

        public boolean succeeded() {
            return this.exitCode == 0;
        }
    }

    /**
     * What one exec run may carry beyond its argv.
     *
     * AIDEV-NOTE: there is deliberately no {@code user} here. The identity an exec runs as
     * is the WORKLOAD's ({@code InstanceSpec.runUser}), never the caller's choice -- a
     * per-call user would make "run this as root inside a tenant workspace" one argument
     * away on a surface whose whole gate is the exec capability.
     *
     * @param env     variables set for this run only; a credential lives here and nowhere
     *                else, which is what keeps a git token out of the volume
     * @param workdir the directory to run in, or null for the image's own
     */
    record ExecOptions(@NonNull Map<String, String> env, @Nullable String workdir) {

        public static @NonNull ExecOptions none() {
            return new ExecOptions(Map.of(), null);
        }

        public static @NonNull ExecOptions in(@Nullable String workdir) {
            return new ExecOptions(Map.of(), workdir);
        }

        public @NonNull ExecOptions withEnv(@NonNull Map<String, String> env) {
            return new ExecOptions(Map.copyOf(env), this.workdir);
        }
    }

    /**
     * Run {@code command} to completion inside the RUNNING workload.
     *
     * @throws IOException when the workload is not running, the daemon is unreachable,
     *                     or the run exceeds the timeout
     */
    default @NonNull ExecOutcome runExec(@NonNull InstanceSpec spec,
                                         @NonNull List<String> command,
                                         long timeoutMs) throws IOException {
        return runExec(spec, command, ExecOptions.none(), timeoutMs);
    }

    /** {@link #runExec(InstanceSpec, List, long)} carrying per-run env and a workdir. */
    @NonNull ExecOutcome runExec(@NonNull InstanceSpec spec, @NonNull List<String> command,
                                 @NonNull ExecOptions options, long timeoutMs)
            throws IOException;
}
