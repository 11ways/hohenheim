package be.elevenways.hohenheim.server.sitetype;

/**
 * The wire protocol used to dial an upstream, independent of the http/https scheme.
 */
public enum UpstreamProtocol {

    /** Plain HTTP/1.1 (over TLS when the upstream URI scheme is https). */
    HTTP1,

    /**
     * HTTP/2: prior-knowledge cleartext (h2c) for http upstreams, ALPN-negotiated
     * h2 for https upstreams. Required for native gRPC backends.
     */
    H2;

    public static UpstreamProtocol fromSetting(Object value) {
        return "h2".equals(value) ? H2 : HTTP1;
    }

    /**
     * @return the Undertow client dial scheme for this protocol + upstream scheme
     */
    public String dialScheme(String uriScheme) {
        boolean secure = "https".equalsIgnoreCase(uriScheme);
        if (this == H2) {
            return secure ? "h2" : "h2c-prior";
        }
        return secure ? "https" : "http";
    }
}
