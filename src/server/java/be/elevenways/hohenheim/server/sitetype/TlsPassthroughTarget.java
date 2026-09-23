package be.elevenways.hohenheim.server.sitetype;

/**
 * Connection target selected for an SNI-based TLS passthrough route.
 *
 * @param publicOnly whether the backend may only be reached at public addresses (a
 *                   tenant-owned site); every resolved address is then vetted at dial time
 */
public record TlsPassthroughTarget(String host, int port, boolean proxyProtocolV2,
                                   int connectTimeoutMillis, boolean publicOnly) {

    public TlsPassthroughTarget {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("TLS passthrough host is required");
        }
        host = host.trim();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("TLS passthrough port must be between 1 and 65535");
        }
        if (connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("TLS passthrough connect timeout must be positive");
        }
    }

    /** An operator-owned target, which may reach any address. */
    public TlsPassthroughTarget(String host, int port, boolean proxyProtocolV2, int connectTimeoutMillis) {
        this(host, port, proxyProtocolV2, connectTimeoutMillis, false);
    }
}
