package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.zenit.server.net.PinnedUpstreamDial;
import org.xnio.OptionMap;

import java.net.URI;

/**
 * Describes an upstream target the proxy dials. For unix-socket upstreams the URI points at a
 * loopback bridge port, so a single TCP-based dial path serves both transports.
 *
 * @param dialOptions connect options the dial must carry, such as a {@link PinnedUpstreamDial}'s
 *                    TLS host name; empty for a target dialed as named
 */
public record UpstreamTarget(URI uri, UpstreamProtocol protocol, boolean ignoreCertificates,
                             OptionMap dialOptions) {

    public UpstreamTarget(URI uri, UpstreamProtocol protocol, boolean ignoreCertificates) {
        this(uri, protocol, ignoreCertificates, OptionMap.EMPTY);
    }

    public UpstreamTarget(URI uri, boolean ignoreCertificates) {
        this(uri, UpstreamProtocol.HTTP1, ignoreCertificates);
    }

    /** @return a target dialed at the address the outbound guard vetted, TLS still naming the host */
    public static UpstreamTarget pinned(PinnedUpstreamDial dial, UpstreamProtocol protocol,
                                        boolean ignoreCertificates) {
        return new UpstreamTarget(dial.uri(), protocol, ignoreCertificates, dial.options());
    }
}
