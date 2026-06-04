package be.elevenways.hohenheim.server.sitetype;

import java.net.URI;

/**
 * Describes an upstream target the proxy dials. For unix-socket upstreams the URI points at a
 * loopback bridge port, so a single TCP-based dial path serves both transports.
 */
public record UpstreamTarget(URI uri, boolean ignoreCertificates) {
}
