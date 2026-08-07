package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.incus.IncusTransport;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An in-memory ECHO of the handful of Incus endpoints {@link IncusInstanceRuntime}
 * touches: whatever is POSTed/PUT is stored verbatim and returned on the next GET,
 * so the isolation ACL and NIC read-back verifications the real driver performs
 * pass on their own merits (the fake never hardcodes an "expected" shape) rather
 * than needing separately-maintained fixture data. An unhandled path throws loudly
 * instead of silently answering success -- a gap here must fail the test, not hide
 * behind a default.
 */
final class FakeIncusTransport implements IncusTransport {

    final Map<String, Object> aclStore = new LinkedHashMap<>();
    final Map<String, String> imageAliases = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> instances = new LinkedHashMap<>();

    /** What GET /state answers for every instance; the resize gate reads this. */
    String instanceStatus = "Stopped";

    Map<String, Object> lastCreateBody;
    boolean execAttempted;
    private int operationCounter;

    @Override
    public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody,
                               long timeoutMs) throws IOException {
        String path = pathAndQuery.contains("?")
            ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;

        if ("GET".equals(method) && path.equals("/1.0/profiles/default")) {
            Map<String, Object> eth0 = new LinkedHashMap<>();
            eth0.put("type", "nic");
            eth0.put("network", "incusbr0");
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "disk");
            root.put("pool", "default-pool");
            root.put("path", "/");
            Map<String, Object> devices = new LinkedHashMap<>();
            devices.put("eth0", eth0);
            devices.put("root", root);
            return sync(Map.of("devices", devices));
        }
        if ("GET".equals(method)
                && path.equals("/1.0/network-acls/" + IncusNetworkPolicy.aclName())) {
            Object stored = this.aclStore.get(IncusNetworkPolicy.aclName());
            return stored == null ? notFound("Network ACL not found") : sync(stored);
        }
        if ("POST".equals(method) && path.equals("/1.0/network-acls")) {
            Map<String, Object> body = parse(jsonBody);
            this.aclStore.put(String.valueOf(body.get("name")), body);
            return sync(body);
        }
        if ("PUT".equals(method)
                && path.equals("/1.0/network-acls/" + IncusNetworkPolicy.aclName())) {
            Map<String, Object> body = parse(jsonBody);
            this.aclStore.put(IncusNetworkPolicy.aclName(), body);
            return sync(body);
        }
        if ("GET".equals(method) && path.startsWith("/1.0/images/aliases/")) {
            String alias = path.substring("/1.0/images/aliases/".length());
            String fingerprint = this.imageAliases.get(alias);
            return fingerprint == null ? notFound("Image alias not found")
                : sync(Map.of("target", fingerprint));
        }
        if ("POST".equals(method) && path.equals("/1.0/instances")) {
            Map<String, Object> body = parse(jsonBody);
            this.lastCreateBody = body;
            Map<String, Object> stored = new LinkedHashMap<>(body);
            stored.put("architecture", "x86_64");
            stored.put("ephemeral", false);
            stored.put("description", "");
            this.instances.put(String.valueOf(body.get("name")), stored);
            return async();
        }
        if ("POST".equals(method) && path.endsWith("/exec")) {
            this.execAttempted = true;
            return async();
        }
        if ("PUT".equals(method) && path.endsWith("/state")) {
            return async();
        }
        if ("PUT".equals(method) && path.startsWith("/1.0/instances/")) {
            Map<String, Object> body = parse(jsonBody);
            String name = path.substring("/1.0/instances/".length());
            this.instances.put(name, new LinkedHashMap<>(body));
            return async();
        }
        if ("GET".equals(method) && path.startsWith("/1.0/instances/")
                && path.endsWith("/state")) {
            String name = path.substring("/1.0/instances/".length(),
                path.length() - "/state".length());
            return this.instances.containsKey(name)
                ? sync(Map.of("status", this.instanceStatus))
                : notFound("Instance not found");
        }
        if ("GET".equals(method) && path.startsWith("/1.0/instances/")
                && !path.contains("/state") && !path.contains("/exec")) {
            String name = path.substring("/1.0/instances/".length());
            Map<String, Object> instance = this.instances.get(name);
            return instance == null ? notFound("Instance not found") : sync(instance);
        }
        if ("GET".equals(method) && path.matches("/1\\.0/operations/.+/wait")) {
            Map<String, Object> operationResult = new LinkedHashMap<>();
            operationResult.put("status_code", 200);
            operationResult.put("status", "Success");
            operationResult.put("description", "fake operation");
            operationResult.put("err", "");
            return sync(operationResult);
        }
        throw new IOException("FakeIncusTransport: unhandled " + method + " " + path);
    }

    private Http11.Raw async() {
        String opPath = "/1.0/operations/op-" + (++this.operationCounter);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "async");
        envelope.put("operation", opPath);
        envelope.put("metadata", Map.of());
        return raw(202, Json.stringify(envelope));
    }

    private static Http11.Raw sync(Object metadata) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "sync");
        envelope.put("metadata", metadata);
        return raw(200, Json.stringify(envelope));
    }

    private static Http11.Raw notFound(String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "error");
        envelope.put("error_code", 404);
        envelope.put("error", message);
        return raw(404, Json.stringify(envelope));
    }

    private static Http11.Raw raw(int status, String body) {
        return new Http11.Raw(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws IOException {
        try {
            return (Map<String, Object>) new Dry().parse(json);
        } catch (Exception malformed) {
            throw new IOException("FakeIncusTransport: bad JSON body: " + json, malformed);
        }
    }

    @Override
    public Http11.Raw exchangeUpload(String method, String pathAndQuery, Path bodyFile,
                                     String contentType, Map<String, String> extraHeaders,
                                     long timeoutMs) {
        throw new UnsupportedOperationException("not exercised by this journey");
    }

    @Override
    public Http11.Raw exchangeDownload(String method, String pathAndQuery,
                                       Path destination, long maxBytes, long timeoutMs) {
        throw new UnsupportedOperationException("not exercised by this journey");
    }

    @Override
    public IncusWebSocket openWebSocket(String pathAndQuery, long connectTimeoutMs) {
        throw new UnsupportedOperationException("not exercised by this journey");
    }

    @Override
    public String describe() {
        return "fake-incus-transport";
    }
}

