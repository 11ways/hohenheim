package be.elevenways.hohenheim.server.sitetype;

import java.net.URI;

/**
 * Callback for proxy-type handlers to forward requests to an upstream.
 * Named UpstreamForwarder (not ProxyCallback) to avoid collision with
 * io.undertow.server.handlers.proxy.ProxyCallback.
 */
public interface UpstreamForwarder {

    default void forwardTo(URI upstream) {
        forwardTo(new UpstreamTarget(upstream, false));
    }

    void forwardTo(UpstreamTarget upstream);
}
