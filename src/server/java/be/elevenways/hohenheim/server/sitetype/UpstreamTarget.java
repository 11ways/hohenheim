package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.zenit.server.net.PinnedUpstreamDial;

import java.net.URI;
import java.util.List;

/**
 * Describes an upstream target the proxy dials. For unix-socket upstreams the URI points at a
 * loopback bridge port, so a single TCP-based dial path serves both transports.
 *
 * @param uri   the upstream as configured: what an upstream redirect is recognised by, and what is
 *              dialed when there are no {@code dials}
 * @param dials the vetted addresses to dial instead, in order (the next when one refuses);
 *              empty for a target dialed as named
 */
public record UpstreamTarget(URI uri, UpstreamProtocol protocol, boolean ignoreCertificates,
                             List<PinnedUpstreamDial> dials) {

    public UpstreamTarget {
        dials = List.copyOf(dials);
    }

    public UpstreamTarget(URI uri, UpstreamProtocol protocol, boolean ignoreCertificates) {
        this(uri, protocol, ignoreCertificates, List.of());
    }

    public UpstreamTarget(URI uri, boolean ignoreCertificates) {
        this(uri, UpstreamProtocol.HTTP1, ignoreCertificates);
    }

    /**
     * @param upstream the upstream as configured, naming its host
     * @param dials    one dial per vetted address of that host
     * @return a target dialed at the addresses the outbound guard vetted, TLS still naming the host
     * @throws IllegalArgumentException when there is no dial
     */
    public static UpstreamTarget pinned(URI upstream, List<PinnedUpstreamDial> dials, UpstreamProtocol protocol,
                                        boolean ignoreCertificates) {
        if (dials.isEmpty()) {
            throw new IllegalArgumentException("A pinned upstream needs a vetted address to dial");
        }
        return new UpstreamTarget(upstream, protocol, ignoreCertificates, dials);
    }
}
