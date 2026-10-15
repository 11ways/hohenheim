package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.ports.StreamProtocol;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One stream's public port and where it forwards.
 *
 * @param listenPort       the public port on the proxy's host
 * @param protocol         the transports forwarded on it
 * @param forwardHost      the upstream's host name or address
 * @param forwardPort      the upstream's port
 * @param connectTimeoutMs how long a TCP connect to the upstream may take
 * @param publicOnly       whether only public addresses may be dialled (a tenant-owned site)
 */
public record StreamTarget(int listenPort, @NonNull StreamProtocol protocol, @NonNull String forwardHost,
                           int forwardPort, int connectTimeoutMs, boolean publicOnly) {
}
