package be.elevenways.hohenheim.server.sitetype;

import java.net.URI;

/**
 * Describes an upstream target and its transport options.
 */
public record UpstreamTarget(URI uri, boolean ignoreCertificates, String socketPath) {

    public UpstreamTarget(URI uri, boolean ignoreCertificates) {
        this(uri, ignoreCertificates, null);
    }
}
