package be.elevenways.hohenheim.server.docker;

import java.io.IOException;

/**
 * Carries a single Docker Engine HTTP exchange to a daemon, regardless of how that daemon is
 * reached (local unix socket, or a remote daemon over SSH via {@code docker system dial-stdio}).
 * Implementations write the full raw request and read the complete raw response (to EOF), so the
 * HTTP framing/parsing in {@link DockerClient} stays transport-agnostic.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public interface DockerTransport {

    /**
     * Send {@code request} (a complete HTTP/1.1 request with {@code Connection: close}) and return
     * the entire raw response, bounded by {@code timeoutMs}.
     */
    byte[] roundTrip(byte[] request, long timeoutMs) throws IOException;
}
