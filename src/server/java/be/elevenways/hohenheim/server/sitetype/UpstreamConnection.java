package be.elevenways.hohenheim.server.sitetype;

import java.net.URI;

/**
 * A resolved upstream the proxy can dial. TCP upstreams expose their real URI; unix-socket upstreams
 * are presented as a loopback TCP URI by a bridge. {@link #close()} releases any bridge it owns. New
 * transports (e.g. a future native AF_UNIX client) plug in as additional implementations -- the proxy
 * dial path only depends on this interface.
 */
public interface UpstreamConnection {

    /** The URI Undertow's proxy client dials (the real upstream for TCP, the bridge loopback for unix). */
    URI connectUri();

    /** Whether upstream TLS certificate verification is skipped. */
    boolean ignoreCertificates();

    /** Release transport resources (a bridge); no-op for plain TCP. */
    default void close() {}
}
