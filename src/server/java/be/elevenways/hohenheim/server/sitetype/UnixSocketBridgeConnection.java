package be.elevenways.hohenheim.server.sitetype;

import be.elevenways.hohenheim.server.proxy.UnixSocketBridge;

import java.io.IOException;
import java.net.URI;

/**
 * A unix-socket upstream exposed to the TCP-only Undertow proxy client through a loopback
 * {@link UnixSocketBridge}. Long-lived: created once per socket upstream (a ProxySite handler or a
 * managed child process), reused across requests, and closed when that owner is torn down.
 */
public final class UnixSocketBridgeConnection implements UpstreamConnection {

    private final UnixSocketBridge bridge;
    private final URI connectUri;
    private final boolean ignoreCertificates;

    public UnixSocketBridgeConnection(String socketPath, boolean ignoreCertificates) throws IOException {
        this.bridge = new UnixSocketBridge(socketPath);
        this.connectUri = URI.create("http://127.0.0.1:" + bridge.getPort() + "/");
        this.ignoreCertificates = ignoreCertificates;
    }

    @Override
    public URI connectUri() {
        return connectUri;
    }

    @Override
    public boolean ignoreCertificates() {
        return ignoreCertificates;
    }

    @Override
    public void close() {
        bridge.close();
    }
}
