package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.sitetype.TlsPassthroughTarget;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Supplier;

/** Routes by the SNI hostname of a bounded ClientHello, without completing the handshake. */
final class TlsSniRouter implements ConnectionRouter {

    /** Connecting to a loopback listener in the same process never legitimately takes long. */
    private static final int TERMINATION_CONNECT_TIMEOUT_MS = 5_000;

    @FunctionalInterface
    interface Resolver {
        TlsPassthroughRoutes.Decision resolve(@Nullable String serverName, String listenerIp);
    }

    private final Resolver resolver;
    private final Supplier<@Nullable InetSocketAddress> terminationAddress;

    TlsSniRouter(Resolver resolver, Supplier<@Nullable InetSocketAddress> terminationAddress) {
        this.resolver = resolver;
        this.terminationAddress = terminationAddress;
    }

    @Override
    public Decision route(BufferedInputStream input, InetSocketAddress source,
                          InetSocketAddress destination) throws IOException {
        TlsClientHelloReader.Result hello = TlsClientHelloReader.read(input);
        TlsPassthroughRoutes.Decision decision = resolver.resolve(
            hello.serverName().orElse(null), destination.getAddress().getHostAddress());

        TlsPassthroughTarget passthrough = decision.target();
        if (passthrough != null) {
            return new Decision(BackendChoice.external(passthrough.host(), passthrough.port(),
                passthrough.proxyProtocolV2(), passthrough.connectTimeoutMillis()), hello.replayBytes());
        }
        if (decision.action() == TlsPassthroughRoutes.Action.REJECT) {
            return Decision.reject();
        }

        InetSocketAddress internal = terminationAddress.get();
        if (internal == null) {
            return Decision.reject();
        }
        return new Decision(BackendChoice.internal(internal, TERMINATION_CONNECT_TIMEOUT_MS),
            hello.replayBytes());
    }
}
