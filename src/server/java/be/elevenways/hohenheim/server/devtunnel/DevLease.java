package be.elevenways.hohenheim.server.devtunnel;

import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;

import java.net.URI;
import java.time.Instant;

/**
 * One live dev-site registration inside a dev namespace: the claimed name, the
 * tunnel connection serving it, and the loopback bridge the proxy forwards to.
 */
public final class DevLease {

    private final int siteId;
    private final String name;
    private final String origin;
    private final DevTunnelServerHandler handler;
    private final DevTunnelBridge bridge;
    private final UpstreamTarget target;
    private final Instant registeredAt = Instant.now();
    private final String remoteDescription;

    DevLease(int siteId, String name, String origin, DevTunnelServerHandler handler,
             DevTunnelBridge bridge, String remoteDescription) {
        this.siteId = siteId;
        this.name = name;
        this.origin = origin;
        this.handler = handler;
        this.bridge = bridge;
        this.remoteDescription = remoteDescription;
        this.target = new UpstreamTarget(URI.create("http://127.0.0.1:" + bridge.getPort() + "/"), false);
    }

    public int getSiteId() { return siteId; }
    public String getName() { return name; }
    public String getOrigin() { return origin; }
    public Instant getRegisteredAt() { return registeredAt; }
    public String getRemoteDescription() { return remoteDescription; }
    public int getActiveStreams() { return handler.getActiveStreamCount(); }

    public UpstreamTarget getTarget() { return target; }

    DevTunnelServerHandler getHandler() { return handler; }
    DevTunnelBridge getBridge() { return bridge; }
}
