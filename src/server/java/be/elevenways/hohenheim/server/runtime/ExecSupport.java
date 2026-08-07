package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

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
     * Run {@code command} to completion inside the RUNNING workload.
     *
     * @throws IOException when the workload is not running, the daemon is unreachable,
     *                     or the run exceeds the timeout
     */
    @NonNull ExecOutcome runExec(@NonNull InstanceSpec spec, @NonNull List<String> command,
                                 long timeoutMs) throws IOException;
}
