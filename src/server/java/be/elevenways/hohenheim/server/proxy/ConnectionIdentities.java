package be.elevenways.hohenheim.server.proxy;

import io.undertow.server.HttpServerExchange;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

/** Restores public connection addresses after the public-listener-to-Undertow loopback hop. */
final class ConnectionIdentities {

    private record Identity(InetSocketAddress source, InetSocketAddress destination) {}

    private final ConcurrentHashMap<InetSocketAddress, Identity> identities = new ConcurrentHashMap<>();

    void register(InetSocketAddress internalSource, InetSocketAddress source,
                  InetSocketAddress destination) {
        identities.put(internalSource, new Identity(source, destination));
    }

    void unregister(InetSocketAddress internalSource) {
        identities.remove(internalSource);
    }

    void restore(HttpServerExchange exchange) {
        Identity identity = identities.get(exchange.getSourceAddress());
        if (identity == null) return;
        exchange.setSourceAddress(identity.source());
        exchange.setDestinationAddress(identity.destination());
    }
}
