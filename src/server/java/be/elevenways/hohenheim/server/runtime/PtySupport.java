package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * The INTERACTIVE half of the driver's exec seam: a pseudo-terminal inside a running
 * workload, living for as long as its consumer keeps it. A driver without it does not
 * implement this, and the shell surface refuses by name.
 *
 * AIDEV-NOTE: deliberately a SECOND interface rather than a method on {@link ExecSupport},
 * whose docblock says exactly why: that contract is single-shot and buffered, and bolting
 * a stream onto it is what {@link InstanceRuntime}'s docblock forbids. The two answer to
 * different capabilities too -- {@code exec} is ADMIN, {@code shell} is a delegable tenant
 * verb -- so keeping them apart keeps the driver honest about which one a runtime offers.
 *
 * AIDEV-NOTE: the identity a session runs as is NOT an argument. It is the workload's own
 * {@code InstanceSpec.runUser}, exactly as {@code ExecSupport} decided, for the same
 * reason: a per-call user would put "give me a root shell in this tenant workspace" one
 * argument away on a surface whose whole gate is a capability.
 */
public interface PtySupport {

    /** One live pseudo-terminal: bytes both ways, a size, and a teardown. */
    interface PtySession {

        /**
         * The live byte pipe. {@code next()} yields output until the shell exits;
         * {@code writeStdin} carries keystrokes toward it.
         */
        @NonNull ConsoleStream stream();

        /**
         * Tell the pseudo-terminal its new geometry.
         *
         * @throws IOException when the driver refuses the resize (a finished session
         *         included); never a silent no-op, because a shell that keeps rendering
         *         at 80x24 looks like a broken terminal rather than a refused call
         */
        void resize(int cols, int rows) throws IOException;

        /**
         * Whether the program never really started -- a missing or non-executable
         * interpreter, which the daemon reports only AFTER the exec was accepted.
         *
         * AIDEV-NOTE: the driver owns the numbering. Docker answers this from the exec's
         * exit code (126 = not executable, 127 = not found), which is POSIX shell
         * vocabulary and has no business leaking into the surface that asks the question.
         *
         * @throws IOException when the driver cannot be asked
         */
        boolean failedToStart() throws IOException;

        /** Release the session; idempotent. */
        void close();
    }

    /**
     * Whether THIS runtime instance can actually open a pseudo-terminal.
     *
     * AIDEV-NOTE: a runtime can implement the interface and still be unable to serve it,
     * because the streaming lane is supplied by the KIND that builds the runtime (only
     * kinds that can offer a shell thread it through). The shell surface asks this before
     * it promises anything, so an unwired runtime produces a named refusal instead of an
     * IOException from the middle of a handshake.
     */
    boolean supportsPty();

    /**
     * Open an interactive session running {@code command} inside the RUNNING workload.
     *
     * @throws IOException when the workload is not running, the daemon is unreachable, or
     *         this runtime has no streaming lane
     */
    @NonNull PtySession openPty(@NonNull InstanceSpec spec, @NonNull List<String> command,
                                int cols, int rows) throws IOException;
}
