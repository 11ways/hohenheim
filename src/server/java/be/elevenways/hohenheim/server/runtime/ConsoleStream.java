package be.elevenways.hohenheim.server.runtime;

import java.io.IOException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A live console stream of one workload: incremental output frames, optional stdin
 * writes, and a TYPED end -- the four-way vocabulary a consumer must be able to act on.
 * The Docker implementation is {@code ContainerStream}; the Incus driver will implement
 * this same interface over its own wire shape.
 */
public interface ConsoleStream extends AutoCloseable {

    /** Why the stream is over; the ContainerState ABSENT/UNREACHABLE doctrine's sibling. */
    enum Termination {
        /** This consumer closed the stream; says nothing about the workload. */
        CONSUMER_CLOSED,
        /** The daemon ended the stream in order -- for attach, the workload exited. */
        ENDED,
        /** The transport failed mid-stream: the daemon (or its ssh path) went away. */
        DAEMON_LOST
    }

    /** One output frame; {@code stderr} distinguishes the demultiplexed stream. */
    record Chunk(boolean stderr, byte @NonNull [] data) {}

    /**
     * Blocking pull of the next output frame.
     *
     * @return the next chunk, or null once the stream terminated ({@link #termination()}
     *         then says why); every call after termination keeps returning null
     */
    @Nullable Chunk next();

    /**
     * Write to the workload's stdin.
     *
     * @throws IOException when the transport refuses or the stream is over
     */
    void writeStdin(byte @NonNull [] data) throws IOException;

    /**
     * @throws IllegalStateException while the stream is still live
     */
    @NonNull Termination termination();

    /** Transport evidence for the termination (error message, ssh stderr), or empty. */
    @NonNull String detail();

    /** Idempotent; a blocked {@link #next()} unblocks with CONSUMER_CLOSED. */
    @Override
    void close();
}
