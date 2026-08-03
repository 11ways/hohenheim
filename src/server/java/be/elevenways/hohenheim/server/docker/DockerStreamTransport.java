package be.elevenways.hohenheim.server.docker;

import java.io.IOException;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE SECOND transport contract, beside {@link DockerTransport}: a long-lived,
 * incrementally-read, bidirectional exchange for the endpoints the single-shot
 * {@code roundTrip} cannot represent -- follow-logs, attach-with-stdin, exec streams,
 * stats. Verified twice before this landed: {@code roundTrip} reads to EOF with no
 * callback and no post-request writes, so streaming is a new contract, never a patch.
 *
 * Both shipped transports implement both contracts, so the ssh lane keeps riding the
 * ONE pinned argv from {@code HostKeys.sshArgv} -- a streaming connection must never
 * grow a second ssh argv path.
 */
public interface DockerStreamTransport {

    /**
     * Connect, send {@code request} (a complete HTTP/1.1 request head, WITHOUT
     * {@code Connection: close} semantics mattering -- the stream lives until either
     * side closes), and return the live connection. Response parsing is the caller's
     * job ({@link ContainerStream}); this only moves bytes.
     *
     * @param connectTimeoutMs bound on connect + request write; the returned stream
     *                         itself is deliberately unbounded in time
     * @throws IOException when the daemon cannot be reached or the request not written
     */
    @NonNull DockerStreamConnection openStream(byte @NonNull [] request, long connectTimeoutMs)
        throws IOException;
}
