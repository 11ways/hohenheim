package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.hohenheim.server.util.EnvVars;
import be.elevenways.protoblast.common.Blast;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a single container image as a managed site and reverse-proxies to it.
 * The container's port is published to an ephemeral {@code 127.0.0.1} host port,
 * which becomes the proxy upstream.
 *
 * AIDEV-NOTE: The container is started synchronously in the constructor (after a
 * pull-if-missing). A long pull therefore blocks this site's load; SiteDispatcher
 * isolates that per-site. Making start async (like GitDeployment's queue) is a
 * follow-up. On any failure the handler stays DOWN rather than throwing.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class DockerSiteRequestHandler implements SiteRequestHandler {

    /** The one bind address a docker site's port is published on, and claimed under. */
    private static final String HOST_BIND_ADDRESS = "127.0.0.1";

    private final int siteId;
    private final DockerClient docker;
    private final String containerName;

    private volatile String containerId;
    private volatile URI upstream;

    public DockerSiteRequestHandler(int siteId, Map<String, Object> settings) {
        this.siteId = siteId;
        this.docker = dockerFor(settings);
        this.containerName = "hohenheim-site-" + siteId;

        Integer port = asInt(settings.get("container_port"));
        if (port == null || port <= 0) {
            Blast.log("DOCKER: site", siteId, "missing container_port; staying down");
            return;
        }

        String buildContext = (String) settings.get("build_context");
        String image = (String) settings.get("image");

        try {
            String imageRef;
            if (buildContext != null && !buildContext.isBlank()) {
                // Git-provisioned: build the image from the checkout's Dockerfile.
                imageRef = "hohenheim-site-" + siteId + ":latest";
                docker.buildImage(Path.of(buildContext), imageRef, (String) settings.get("dockerfile"));
            } else if (image != null && !image.isBlank()) {
                // Run a remote image, pulling it if absent.
                String tag = (String) settings.get("tag");
                String resolvedTag = (tag == null || tag.isBlank()) ? "latest" : tag;
                docker.ensureImage(image, resolvedTag);
                imageRef = image.contains(":") ? image : image + ":" + resolvedTag;
            } else {
                Blast.log("DOCKER: site", siteId, "needs an image or a git source; staying down");
                return;
            }

            removeExisting();   // clean up a leftover container from a previous run
            // Record the id before starting so a failed start/upstream-resolve is torn down by destroy().
            this.containerId = docker.createContainer(containerName, buildSpec(siteId, imageRef, port, settings));
            docker.startContainer(this.containerId);
            this.upstream = resolveUpstream(this.containerId, port);
            recordPublishedPort(settings, this.upstream.getPort());
        } catch (IOException e) {
            Blast.log("DOCKER: failed to start site", siteId, "-", e.getMessage());
            destroy();   // tear down a partial start so a retry is clean
        }
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

    @Override
    public SiteHealth getHealth() {
        if (containerId == null) {
            return SiteHealth.DOWN;
        }
        try {
            Object state = docker.inspectContainer(containerId).get("State");
            boolean running = state instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("Running"));
            return running ? SiteHealth.UP : SiteHealth.DOWN;
        } catch (IOException e) {
            return SiteHealth.DOWN;
        }
    }

    @Override
    public void destroy() {
        if (containerId == null) {
            return;
        }
        try {
            docker.stopContainer(containerId, 10);
        } catch (IOException ignored) {
            // proceed to force-remove regardless
        }
        try {
            docker.removeContainer(containerId, true);
        } catch (IOException ignored) {
            // best effort
        }
        PortLedger.releaseOwner(SiteModel.MODEL_ID, siteId);
        containerId = null;
        upstream = null;
    }

    /** The resolved proxy upstream (127.0.0.1:&lt;published port&gt;), or null when down. */
    public URI getUpstream() {
        return upstream;
    }

    // -----------------------------------------------------------------------

    // Resolve the daemon for this site's target server. An absent/blank setting is a direct
    // local client (no inventory lookup, so it works without the DB); any stored value goes
    // through THE canonical host-key derivation (ServerModel) -- never a local re-spelling.
    private static DockerClient dockerFor(Map<String, Object> settings) {
        Object server = settings.get("server");
        if (server == null || server.toString().isBlank()) {
            return new DockerClient();
        }
        return new ServerService().clientFor(ServerModel.nameOf(ServerModel.canonicalServerId(server)));
    }

    private void removeExisting() {
        try {
            docker.removeContainer(containerName, true);
        } catch (IOException ignored) {
            // 404 when there is nothing to remove -- expected
        }
    }

    private static Map<String, Object> buildSpec(int siteId, String imageRef, int port,
                                                 Map<String, Object> settings) {
        String portKey = port + "/tcp";
        Map<String, String> owner = OwnerLabels.of(SiteModel.MODEL_ID, siteId);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", imageRef);
        spec.put("Labels", owner);

        String command = (String) settings.get("command");
        if (command != null && !command.isBlank()) {
            spec.put("Cmd", List.of(command.trim().split("\\s+")));
        }

        List<String> env = buildEnv(siteId, settings.get("environment_variables"));
        if (!env.isEmpty()) {
            spec.put("Env", env);
        }

        spec.put("ExposedPorts", Map.of(portKey, Map.of()));

        Map<String, Object> hostConfig = new LinkedHashMap<>();
        hostConfig.put("PortBindings", Map.of(portKey, List.of(Map.of("HostIp", HOST_BIND_ADDRESS, "HostPort", ""))));

        // Persistent named volumes (logical name -> container path); the stable volume
        // name keys on the site id, so data survives redeploys and container swaps.
        // VolumeOptions.Labels stamp the owner onto the volume the daemon creates for
        // the mount -- the container is relabelled for free on every recreate, but a
        // volume keeps whatever labels it was BORN with (Docker never relabels one).
        List<Map<String, Object>> mounts = new ArrayList<>();
        EnvVars.toMap(settings.get("volumes")).forEach((name, containerPath) -> {
            if (containerPath == null || containerPath.isBlank()) {
                return;
            }
            mounts.add(Map.of(
                "Type", "volume",
                "Source", "hohenheim-site-" + siteId + "-vol-" + name,
                "Target", containerPath.trim(),
                "VolumeOptions", Map.of("Labels", owner)));
        });
        if (!mounts.isEmpty()) {
            hostConfig.put("Mounts", mounts);
        }

        ResourceLimits.fromSettings(settings).applyTo(hostConfig);
        spec.put("HostConfig", hostConfig);
        return spec;
    }

    /**
     * Record-after: the kernel picked the host port, so the ledger learns it here, owned
     * by the site record. Deliberately the LAST step of the start sequence.
     *
     * AIDEV-NOTE: the claim is TCP-only and that is a property of the readback, not a
     * simplification -- DockerClient.publishedPort looks up "{port}/tcp" in the inspect
     * output and can see no other protocol, so record-after structurally cannot serve a
     * UDP publication. A UDP-publishing site needs the DECLARED pre-allocation mode (the
     * decided fork 2), not a wider readback bolted on here. Bind address 127.0.0.1
     * matches buildSpec's PortBindings exactly; PortLedger.conflictingHolder is what makes
     * that specific-address claim exclude a whole-host claim of the same port.
     */
    private void recordPublishedPort(Map<String, Object> settings, int hostPort) {
        PortLedger.recordObserved(ServerModel.canonicalServerId(settings.get("server")),
            HOST_BIND_ADDRESS, hostPort, "tcp", SiteModel.MODEL_ID, siteId, null);
    }

    private URI resolveUpstream(String id, int port) throws IOException {
        int hostPort = docker.publishedPort(id, port);
        try {
            return new URI("http", null, HOST_BIND_ADDRESS, hostPort, "/", null, null);
        } catch (URISyntaxException e) {
            throw new IOException("Bad upstream URI for site " + siteId, e);
        }
    }

    // Injected database variables come first; docker resolves duplicate Env entries
    // last-wins, so operator-authored variables override injected ones. (Docker sites
    // currently can't LINK databases -- a bridge-network container can't reach the
    // host's 127.0.0.1-published port -- so this resolves empty until shared-network
    // mode lands; the wiring keeps the mechanism uniform across handler types.)
    private static List<String> buildEnv(int siteId, Object envObj) {
        List<String> result = new ArrayList<>();
        DatabaseEnvInjection.envForSite(siteId).forEach((name, value) -> result.add(name + "=" + value));
        EnvVars.toMap(envObj).forEach((name, value) -> result.add(name + "=" + value));
        return result;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
