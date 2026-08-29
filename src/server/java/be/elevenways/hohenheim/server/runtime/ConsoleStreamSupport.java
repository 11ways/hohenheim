package be.elevenways.hohenheim.server.runtime;

import java.io.IOException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Optional driver capability (the {@link VolumeSnapshotSupport} shape): live console
 * streaming for one workload. A driver that lacks it is refused BY NAME by the console
 * hub, never silently skipped. This interface is exactly what the Incus driver must
 * implement for its console: an incremental {@link ConsoleStream}, a single-shot tail,
 * and the exit code observation crash detection needs.
 */
public interface ConsoleStreamSupport {

    /**
     * The geometry a pseudo-terminal workload starts with, until a viewer connects and
     * sizes it to its own terminal (a PTY nobody sized reports 0x0, which no TUI survives).
     */
    int INITIAL_COLS = 100;
    int INITIAL_ROWS = 24;

    /** The terminal type a pseudo-terminal workload is told it renders on. */
    String TERM_VARIABLE = "TERM";
    String TERM_VALUE = "xterm-256color";

    /**
     * A console attachment: the stream, whether stdin writes actually REACH the workload,
     * and whether the workload sits behind a pseudo-terminal. Docker discards attach stdin
     * silently when the container was created without {@code OpenStdin}; carrying the fact
     * here is what lets the hub refuse a console command loudly instead of reporting
     * success for a dropped write.
     *
     * @param interactive true when the primary process has a pseudo-terminal
     *        ({@code ConsoleKind.TTY}): the stream echoes, output is raw terminal bytes,
     *        and the viewer's keystrokes and resize frames belong on it rather than one
     *        command line per POST
     */
    record Console(@NonNull ConsoleStream stream, boolean stdinDelivered, boolean interactive) {}

    /**
     * Attach to the workload's console (live stdout/stderr, stdin where delivered).
     * Legal on a CREATED-but-not-started workload, so a caller can attach BEFORE
     * start and miss no output -- unless {@link #attachRequiresRunning()} declares
     * otherwise.
     */
    @NonNull Console openConsole(@NonNull String handle) throws IOException;

    /**
     * Tell an interactive workload's pseudo-terminal its new geometry.
     *
     * @throws IOException when the workload has no pseudo-terminal, is not running, or the
     *         driver cannot express a resize -- never a silent no-op, because a TUI stuck
     *         at its opening size looks like breakage rather than a refused call
     */
    default void resizeConsole(@NonNull String handle, int cols, int rows) throws IOException {
        throw new IOException("This runtime has no console geometry to set for '" + handle + "'");
    }

    /**
     * Whether {@link #openConsole} only works on a RUNNING workload (Incus refuses a
     * console on a stopped instance). The console hub then attaches AFTER start and
     * seeds the matcher from {@link #consoleTail} to close the start-to-attach gap.
     */
    default boolean attachRequiresRunning() {
        return false;
    }

    /** Single-shot console history: the last {@code lines} lines. */
    @NonNull String consoleTail(@NonNull String handle, int lines) throws IOException;

    /**
     * The workload's exit code once it stopped.
     *
     * @return the exit code, or null while it is still running
     * @throws IOException when the daemon cannot be asked or the workload is absent
     */
    @Nullable Integer exitCode(@NonNull String handle) throws IOException;
}
