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
     * A console attachment: the stream plus whether stdin writes actually REACH the
     * workload. Docker discards attach stdin silently when the container was created
     * without {@code OpenStdin}; carrying the fact here is what lets the hub refuse a
     * console command loudly instead of reporting success for a dropped write.
     */
    record Console(@NonNull ConsoleStream stream, boolean stdinDelivered) {}

    /**
     * Attach to the workload's console (live stdout/stderr, stdin where delivered).
     * Legal on a CREATED-but-not-started workload, so a caller can attach BEFORE
     * start and miss no output.
     */
    @NonNull Console openConsole(@NonNull String handle) throws IOException;

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
