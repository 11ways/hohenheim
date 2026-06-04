package be.elevenways.hohenheim.server.sitetype;

import java.net.URI;

/**
 * A plain TCP/HTTP(S) upstream dialed directly by Undertow. No resources to release.
 */
public record TcpUpstreamConnection(URI connectUri, boolean ignoreCertificates) implements UpstreamConnection {
}
