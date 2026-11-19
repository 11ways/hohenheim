package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.ports.StreamProtocol;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One stream's public port and where it forwards.
 *
 * @param listenPort           the public port on the proxy's host
 * @param protocol             the transports forwarded on it
 * @param forwardHost          the upstream's host name or address
 * @param forwardPort          the upstream's port
 * @param connectTimeoutMs     how long a TCP connect to the upstream may take
 * @param publicOnly           whether only public addresses may be dialled (a tenant-owned site)
 * @param sendsProxyProtocolV2 whether each TCP connection to the upstream starts with a PROXY v2 header
 */
public record StreamTarget(int listenPort, @NonNull StreamProtocol protocol, @NonNull String forwardHost,
                           int forwardPort, int connectTimeoutMs, boolean publicOnly, boolean sendsProxyProtocolV2) {

    /** @throws IllegalArgumentException for a PROXY v2 header on a stream that forwards UDP, which never carries one */
    public StreamTarget {
        if (sendsProxyProtocolV2 && protocol.forwardsUdp()) {
            throw new IllegalArgumentException("a PROXY v2 header is sent on TCP streams only, and this one forwards "
                + protocol.token());
        }
    }
}
