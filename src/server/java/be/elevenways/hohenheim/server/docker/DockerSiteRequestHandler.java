package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.validation.Violations;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/**
 * Serves a Docker site by reverse-proxying to its OWNED INSTANCE -- the canonical
 * runtime-resource contract. This handler owns no container: {@link SiteInstances}
 * converges the site's {@code release} instance through {@link
 * be.elevenways.hohenheim.server.instance.InstanceService} (fenced outcome writes,
 * verified destroy, ledger record-after, owner labels), and this class only reads the
 * published loopback port back as its upstream. There is deliberately NO fallback
 * container path: when the contract refuses, the site is DOWN, loudly.
 *
 * AIDEV-NOTE: construction converges synchronously (a long pull/build blocks this
 * site's load; SiteDispatcher isolates that per-site, as before). An unchanged,
 * already-running release is REUSED -- a routing reload no longer restarts every
 * Docker site, which the pre-lowering handler did on every reload.
 */
public class DockerSiteRequestHandler implements SiteRequestHandler {

    /** Loopback, the one address the instance tier publishes record-after ports on. */
    private static final String HOST_BIND_ADDRESS = "127.0.0.1";

    private final int siteId;

    private volatile Integer instanceId;
    private volatile URI upstream;

    public DockerSiteRequestHandler(int siteId, Map<String, Object> settings) {
        this(siteId, null, settings);
    }

    public DockerSiteRequestHandler(int siteId, String siteName, Map<String, Object> settings) {
        this.siteId = siteId;
        try {
            SiteInstances.SiteRuntime runtime =
                SiteInstances.ensureRunning(siteId, siteName, settings);
            this.instanceId = runtime.instanceId();
            Integer port = runtime.status().publishedPort();
            if (port != null) {
                this.upstream = new URI("http", null, HOST_BIND_ADDRESS, port, "/", null, null);
            } else {
                Blast.log("DOCKER: site", siteId, "instance runs without a published port"
                    + " (missing container_port?); staying down");
            }
        } catch (Violations refused) {
            Blast.log("DOCKER: site", siteId, "runtime refused -", refused.getMessage());
        } catch (RuntimeException | URISyntaxException e) {
            Blast.log("DOCKER: failed to start site", siteId, "-", e.getMessage());
        }
        // Register even when down: this generation now answers for the site's runtime,
        // and a later route drop must be able to stop a half-started release.
        SiteInstances.activate(siteId, this);
    }

    @Override
    public int getSiteId() {
        return siteId;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange, UpstreamForwarder forwarder) {
        URI target = upstream;
        if (target == null) {
            exchange.setStatusCode(503);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Container not running");
            return;
        }
        forwarder.forwardTo(new UpstreamTarget(target, false));
    }

    /**
     * AIDEV-NOTE: a workload-dead container is DEGRADED, not DOWN, and routing is
     * deliberately left alone. The kernel killed a process inside the cgroup; the
     * entrypoint (and possibly the app) may still be answering, so blackholing live
     * traffic on that evidence would swap one false report for a worse one. The
     * operator gets told; the requests keep flowing.
     */
    @Override
    public SiteHealth getHealth() {
        Integer id = this.instanceId;
        if (id == null || this.upstream == null) {
            return SiteHealth.DOWN;
        }
        InstanceStatus status = SiteInstances.liveStatus(this.siteId);
        if (status == null || status.state() != ContainerState.RUNNING) {
            return SiteHealth.DOWN;
        }
        return status.workloadDead() ? SiteHealth.DEGRADED : SiteHealth.UP;
    }

    /**
     * Route drop (disable, soft delete, settings edit, generation swap). Only the
     * generation still responsible for the site stops the workload -- SiteDispatcher
     * creates the replacing generation BEFORE destroying this one, and both share the
     * same owned instance, so a superseded generation must not touch it. The actual
     * stop is InstanceService.stop: verified observed release, parked claims on
     * failure, never a lie (the C6 discipline, now inherited instead of reimplemented).
     */
    @Override
    public void destroy() {
        if (!SiteInstances.deactivate(this.siteId, this)) {
            return;
        }
        SiteInstances.stopFor(this.siteId);
        this.upstream = null;
    }

    /** The resolved proxy upstream (127.0.0.1:&lt;published port&gt;), or null when down. */
    public URI getUpstream() {
        return upstream;
    }

    /** The owned instance backing this site's runtime, or null when none was ensured. */
    public Integer getInstanceId() {
        return instanceId;
    }
}
