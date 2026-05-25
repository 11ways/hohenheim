package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.UpstreamForwarder;
import be.elevenways.hohenheim.server.sitetype.UpstreamTarget;
import be.elevenways.protoblast.common.Blast;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.net.URI;
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

    private final int siteId;
    private final DockerClient docker;
    private final String containerName;

    private volatile String containerId;
    private volatile URI upstream;

    public DockerSiteRequestHandler(int siteId, Map<String, Object> settings) {
        this.siteId = siteId;
        this.docker = new DockerClient();
        this.containerName = "hohenheim-site-" + siteId;

        String image = (String) settings.get("image");
        String tag = (String) settings.getOrDefault("tag", "latest");
        Integer port = asInt(settings.get("container_port"));
        if (image == null || image.isBlank() || port == null || port <= 0) {
            Blast.log("DOCKER: site", siteId, "missing image/container_port; staying down");
            return;
        }

        try {
            ensureImage(image, (tag == null || tag.isBlank()) ? "latest" : tag);
            removeExisting();   // clean up a leftover container from a previous run
            String id = docker.createContainer(containerName, buildSpec(image, tag, port, settings));
            docker.startContainer(id);
            this.containerId = id;
            this.upstream = resolveUpstream(id, port);
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
        containerId = null;
        upstream = null;
    }

    /** The resolved proxy upstream (127.0.0.1:&lt;published port&gt;), or null when down. */
    public URI getUpstream() {
        return upstream;
    }

    // -----------------------------------------------------------------------

    private void ensureImage(String image, String tag) throws IOException {
        String ref = image.contains(":") ? image : image + ":" + tag;
        for (Object entry : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) entry).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(ref)) {
                return;
            }
        }
        docker.pullImage(image, tag);
    }

    private void removeExisting() {
        try {
            docker.removeContainer(containerName, true);
        } catch (IOException ignored) {
            // 404 when there is nothing to remove -- expected
        }
    }

    private static Map<String, Object> buildSpec(String image, String tag, int port,
                                                 Map<String, Object> settings) {
        String portKey = port + "/tcp";
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", image.contains(":") ? image : image + ":" + tag);

        String command = (String) settings.get("command");
        if (command != null && !command.isBlank()) {
            spec.put("Cmd", List.of(command.trim().split("\\s+")));
        }

        List<String> env = buildEnv(settings.get("environment_variables"));
        if (!env.isEmpty()) {
            spec.put("Env", env);
        }

        spec.put("ExposedPorts", Map.of(portKey, Map.of()));
        spec.put("HostConfig", Map.of(
            "PortBindings", Map.of(portKey, List.of(Map.of("HostIp", "127.0.0.1", "HostPort", "")))
        ));
        return spec;
    }

    private URI resolveUpstream(String id, int port) throws IOException {
        Object networkSettings = docker.inspectContainer(id).get("NetworkSettings");
        Object ports = networkSettings instanceof Map<?, ?> ns ? ns.get("Ports") : null;
        Object bindings = ports instanceof Map<?, ?> p ? p.get(port + "/tcp") : null;
        if (bindings instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> binding) {
            Object hostPort = binding.get("HostPort");
            if (hostPort != null && !hostPort.toString().isBlank()) {
                try {
                    return new URI("http", null, "127.0.0.1", Integer.parseInt(hostPort.toString()), "/", null, null);
                } catch (Exception e) {
                    throw new IOException("Bad published port '" + hostPort + "' for site " + siteId);
                }
            }
        }
        throw new IOException("Container for site " + siteId + " did not publish port " + port);
    }

    private static List<String> buildEnv(Object envObj) {
        List<String> result = new ArrayList<>();
        if (envObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry) {
                    Object name = entry.get("name");
                    if (name != null && !name.toString().isBlank()) {
                        Object value = entry.get("value");
                        result.add(name + "=" + (value == null ? "" : value));
                    }
                }
            }
        }
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
