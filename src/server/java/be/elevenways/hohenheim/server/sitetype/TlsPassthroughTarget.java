package be.elevenways.hohenheim.server.sitetype;

/** Connection target selected for an SNI-based TLS passthrough route. */
public record TlsPassthroughTarget(String host, int port, boolean proxyProtocolV2,
                                   int connectTimeoutMillis) {

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
}
