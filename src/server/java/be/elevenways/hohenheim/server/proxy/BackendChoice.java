package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.InetSocketAddress;

/**
 * The backend a routed public connection is relayed to.
 *
 * An internal backend is one of Hohenheim's own loopback listeners: it learns the public
 * connection identity from {@link ConnectionIdentities} rather than a PROXY header, because
 * Undertow owns that socket's protocol from the first byte.
 */
record BackendChoice(@Nullable InetSocketAddress internalAddress, @Nullable String host, int port,
                     boolean sendProxyProtocolV2, int connectTimeoutMillis) {

    BackendChoice {
        if ((internalAddress == null) == (host == null)) {
            throw new IllegalArgumentException("a backend is either internal or an external host");
        }
        if (host != null && (port < 1 || port > 65535)) {
            throw new IllegalArgumentException("backend port must be between 1 and 65535");
        }
        if (internalAddress != null && sendProxyProtocolV2) {
            throw new IllegalArgumentException("internal backends never receive a PROXY header");
        }
        if (connectTimeoutMillis < 1) {
            throw new IllegalArgumentException("backend connect timeout must be positive");
        }
    }

    static BackendChoice internal(InetSocketAddress address, int connectTimeoutMillis) {
        return new BackendChoice(address, null, 0, false, connectTimeoutMillis);
    }

    static BackendChoice external(String host, int port, boolean sendProxyProtocolV2,
                                  int connectTimeoutMillis) {
        return new BackendChoice(null, host, port, sendProxyProtocolV2, connectTimeoutMillis);
    }

    boolean isInternal() {
        return internalAddress != null;
    }
}
