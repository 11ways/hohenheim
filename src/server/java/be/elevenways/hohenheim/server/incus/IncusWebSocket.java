package be.elevenways.hohenheim.server.incus;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * One live Incus websocket: blocking message pulls, binary writes, idempotent close.
 * Control frames (ping/pong/close) are handled inside the implementation; a consumer
 * only ever sees payload messages.
 */
public interface IncusWebSocket extends AutoCloseable {

    /**
     * Blocking pull of the next payload message (text and binary are both returned as
     * their raw bytes -- Incus console/exec data is binary, its control lane is JSON
     * text and the caller knows which socket it opened).
     *
     * @return the message payload, or null once the peer closed the socket in order
     * @throws IOException when the transport failed mid-stream (daemon lost)
     */
    byte @Nullable [] receive() throws IOException;

    /** Send one binary message. */
    void send(byte @NonNull [] data) throws IOException;

    /** Idempotent; a blocked {@link #receive()} unblocks with an orderly null or IOException. */
    @Override
    void close();
}
