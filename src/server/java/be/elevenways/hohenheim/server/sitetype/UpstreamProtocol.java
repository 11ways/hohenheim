package be.elevenways.hohenheim.server.sitetype;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The wire protocol used to dial an upstream, independent of the http/https scheme: THE
 * declaring home of the {@code upstream_protocol} setting's stored tokens.
 */
public enum UpstreamProtocol {

    /** Plain HTTP/1.1 (over TLS when the upstream URI scheme is https). */
    HTTP1("http1", "HTTP/1.1"),

    /**
     * HTTP/2: prior-knowledge cleartext (h2c) for http upstreams, ALPN-negotiated
     * h2 for https upstreams. Required for native gRPC backends.
     */
    H2("h2", "HTTP/2 (gRPC)");

    private final String token;
    private final String title;

    UpstreamProtocol(String token, String title) {
        this.token = token;
        this.title = title;
    }

    /** The stored setting value. */
    public @NonNull String token() {
        return this.token;
    }

    /** The unlocalized option title the settings form falls back to. */
    public @NonNull String title() {
        return this.title;
    }

    /**
     * The protocol a stored setting names.
     *
     * AIDEV-NOTE: an ABSENT or blank value is HTTP/1.1 (every site written before the field
     * existed), but an unknown token FAILS CLOSED: it used to fold silently to HTTP/1.1, so a
     * typo or a value from a newer build dialed a gRPC backend over the wrong protocol. The
     * throw lands in the dispatcher's handler-creation guard, which skips the site and
     * records it as a routing problem.
     *
     * @throws IllegalArgumentException when the value is not a known token
     */
    public static @NonNull UpstreamProtocol fromSetting(@Nullable Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return HTTP1;
        }
        String token = String.valueOf(value).trim();
        for (UpstreamProtocol protocol : values()) {
            if (protocol.token.equals(token)) {
                return protocol;
            }
        }
        throw new IllegalArgumentException("Unknown upstream_protocol '" + token + "'");
    }

    /**
     * @return the Undertow client dial scheme for this protocol + upstream scheme
     */
    public String dialScheme(String uriScheme) {
        boolean secure = "https".equalsIgnoreCase(uriScheme);
        return switch (this) {
            case H2 -> secure ? "h2" : "h2c-prior";
            case HTTP1 -> secure ? "https" : "http";
        };
    }
}
