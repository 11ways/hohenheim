package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedInputStream;
import java.net.InetSocketAddress;
import java.util.function.Supplier;

/**
 * Hands every connection straight to an internal listener without reading its protocol.
 *
 * This exists so a public port whose protocol Hohenheim already terminates can still sit
 * behind the shared PROXY v2 ingress front; it deliberately consumes nothing.
 */
final class InternalListenerRouter implements ConnectionRouter {

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final Supplier<@Nullable InetSocketAddress> address;

    InternalListenerRouter(Supplier<@Nullable InetSocketAddress> address) {
        this.address = address;
    }

    @Override
    public Decision route(BufferedInputStream input, InetSocketAddress source,
                          InetSocketAddress destination) {
        InetSocketAddress target = address.get();
        if (target == null) {
            return Decision.reject();
        }
        return new Decision(BackendChoice.internal(target, CONNECT_TIMEOUT_MS), NOTHING_CONSUMED);
    }
}
