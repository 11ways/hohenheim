package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An in-memory Incus daemon over what a controller sweep touches: the network-ACL and network collections and the
 * resource inventory, with named deletes it refuses the way a real daemon refuses an object in use.
 *
 * AIDEV-NOTE: an unhandled request fails the test instead of answering success, so a sweep that starts asking the
 * daemon something new shows up here rather than passing on a default.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class EchoIncusDaemon implements IncusTransport {

    private static final String ACLS = "/1.0/network-acls";
    private static final String NETWORKS = "/1.0/networks";

    /** The network ACLs, by name; a stored object carries the {@code used_by} a real daemon reports. */
    public final Map<String, Map<String, Object>> acls = new LinkedHashMap<>();

    /** The networks, by name. */
    public final Map<String, Map<String, Object>> networks = new LinkedHashMap<>();

    /** Names whose DELETE the daemon refuses as in use. */
    public final Set<String> refusedDeletes = new HashSet<>();

    /** What {@code GET /1.0/resources} answers. */
    public Map<String, Object> resources = Map.of();

    /** @return a client talking to this daemon */
    public @NonNull IncusClient client() {
        return new IncusClient(this);
    }

    @Override
    public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody, long timeoutMs)
            throws IOException {
        String path = pathAndQuery.contains("?")
            ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
        if ("GET".equals(method) && path.equals("/1.0/resources")) {
            return sync(this.resources);
        }
        if ("GET".equals(method) && path.equals(ACLS)) {
            return sync(List.copyOf(this.acls.values()));
        }
        if ("GET".equals(method) && path.equals(NETWORKS)) {
            return sync(List.copyOf(this.networks.values()));
        }
        if ("POST".equals(method) && path.equals(ACLS)) {
            Map<String, Object> body = parse(jsonBody);
            body.putIfAbsent("used_by", List.of());
            this.acls.put(String.valueOf(body.get("name")), body);
            return sync(body);
        }
        Map<String, Map<String, Object>> store = path.startsWith(ACLS + "/") ? this.acls
            : path.startsWith(NETWORKS + "/") ? this.networks : null;
        if (store == null) {
            throw new AssertionError("EchoIncusDaemon: unhandled " + method + " " + pathAndQuery);
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        Map<String, Object> object = store.get(name);
        if (object == null) {
            return error(404, "not found");
        }
        if ("GET".equals(method)) {
            return sync(object);
        }
        if ("PUT".equals(method)) {
            Map<String, Object> body = parse(jsonBody);
            body.put("used_by", object.getOrDefault("used_by", List.of()));
            store.put(name, body);
            return sync(body);
        }
        if ("DELETE".equals(method)) {
            if (this.refusedDeletes.contains(name)) {
                return error(400, "The network is currently in use");
            }
            store.remove(name);
            return sync(Map.of());
        }
        throw new AssertionError("EchoIncusDaemon: unhandled " + method + " " + pathAndQuery);
    }

    private static Http11.Raw sync(Object metadata) {
        return envelope(200, Map.of("type", "sync", "metadata", metadata));
    }

    private static Http11.Raw error(int status, String message) {
        return envelope(status, Map.of("type", "error", "error_code", status, "error", message));
    }

    private static Http11.Raw envelope(int status, Map<String, Object> body) {
        return new Http11.Raw(status, Map.of(), Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws IOException {
        try {
            return new LinkedHashMap<>((Map<String, Object>) new Dry().parse(json));
        } catch (Exception malformed) {
            throw new IOException("EchoIncusDaemon: bad JSON body: " + json, malformed);
        }
    }

    @Override
    public Http11.Raw exchangeUpload(String method, String pathAndQuery, Path bodyFile, String contentType,
                                     Map<String, String> extraHeaders, long timeoutMs) {
        throw new UnsupportedOperationException("a sweep never uploads");
    }

    @Override
    public Http11.Raw exchangeDownload(String method, String pathAndQuery, Path destination, long maxBytes,
                                       long timeoutMs) {
        throw new UnsupportedOperationException("a sweep never downloads");
    }

    @Override
    public IncusWebSocket openWebSocket(String pathAndQuery, long connectTimeoutMs) {
        throw new UnsupportedOperationException("a sweep opens no socket");
    }

    @Override
    public String describe() {
        return "echo-incus-daemon";
    }
}
