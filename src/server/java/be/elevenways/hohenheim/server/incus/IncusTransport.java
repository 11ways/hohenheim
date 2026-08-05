package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.util.Http11;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * Carries Incus API exchanges to one daemon: single-shot REST calls plus the WEBSOCKET
 * lane Incus's own streaming endpoints (console, exec, operations) speak. This is a
 * deliberately DIFFERENT shape from {@code DockerTransport}'s byte[] roundTrip: the
 * websocket lane is part of the transport contract from day one, because Incus has no
 * attach-style raw stream to fall back on.
 */
public interface IncusTransport {

    /**
     * One REST exchange (complete request in, complete response out).
     *
     * @param pathAndQuery e.g. {@code /1.0/instances?recursion=1}
     * @param jsonBody     UTF-8 JSON request body, or null
     * @throws IOException when the daemon cannot be reached or the response is malformed;
     *                     a non-2xx STATUS is returned, never thrown -- Incus carries its
     *                     refusal in the envelope and {@code IncusClient} owns that policy
     */
    Http11.@NonNull Raw exchange(@NonNull String method, @NonNull String pathAndQuery,
                                 @Nullable String jsonBody, long timeoutMs) throws IOException;

    /**
     * Open one websocket (RFC 6455 client handshake included) to an Incus endpoint.
     *
     * @throws IOException when the daemon refuses the upgrade or cannot be reached
     */
    @NonNull IncusWebSocket openWebSocket(@NonNull String pathAndQuery, long connectTimeoutMs)
        throws IOException;

    /** Human identity of the peer for failure text, e.g. {@code https://10.0.0.9:8443}. */
    @NonNull String describe();
}
