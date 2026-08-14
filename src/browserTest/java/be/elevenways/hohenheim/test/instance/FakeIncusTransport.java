package be.elevenways.hohenheim.test.instance;

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
import java.util.List;
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
    final Map<String, Object> networkStore = new LinkedHashMap<>();
    final Map<String, String> imageAliases = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> instances = new LinkedHashMap<>();

    /** Custom volumes of the fake's one pool (default-pool), keyed by volume name. */
    final Map<String, Map<String, Object>> customVolumes = new LinkedHashMap<>();

    /** The last alias a POST /1.0/images publish carried, for assertions. */
    String lastPublishedAlias;

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
        // ACL routes take ANY name: the driver now writes the controller presence
        // marker beside the isolation ACL, and both ride the same echo store. The
        // fake adds the empty used_by a real daemon reports, because
        // ControllerPresence.usedBy reads a MISSING field as "referenced" on purpose.
        if ("GET".equals(method) && path.startsWith("/1.0/network-acls/")) {
            Object stored = this.aclStore.get(
                path.substring("/1.0/network-acls/".length()));
            return stored == null ? notFound("Network ACL not found") : sync(stored);
        }
        if ("POST".equals(method) && path.equals("/1.0/network-acls")) {
            Map<String, Object> body = new LinkedHashMap<>(parse(jsonBody));
            body.putIfAbsent("used_by", List.of());
            this.aclStore.put(String.valueOf(body.get("name")), body);
            return sync(body);
        }
        if ("PUT".equals(method) && path.startsWith("/1.0/network-acls/")) {
            Map<String, Object> body = new LinkedHashMap<>(parse(jsonBody));
            body.putIfAbsent("used_by", List.of());
            this.aclStore.put(path.substring("/1.0/network-acls/".length()), body);
            return sync(body);
        }
        // The managed EXTRA-NIC bridge. A real daemon auto-assigns subnets and NAT for
        // an empty config and reports the result as managed, which is exactly what
        // ensureExtraNetwork read-back-verifies -- so the fake answers with a plausible
        // assignment rather than echoing the empty config it was sent, which would fail
        // the verification on the FAKE instead of on anything the driver did.
        if ("GET".equals(method) && path.startsWith("/1.0/networks/")) {
            Object stored = this.networkStore.get(path.substring("/1.0/networks/".length()));
            return stored == null ? notFound("Network not found") : sync(stored);
        }
        if ("POST".equals(method) && path.equals("/1.0/networks")) {
            Map<String, Object> body = new LinkedHashMap<>(parse(jsonBody));
            body.put("managed", true);
            body.put("config", Map.of("ipv4.address", "10.181.7.1/24", "ipv4.nat", "true"));
            this.networkStore.put(String.valueOf(body.get("name")), body);
            return sync(body);
        }
        if ("GET".equals(method) && path.equals("/1.0/storage-pools/default-pool/volumes/custom")) {
            return sync(List.copyOf(this.customVolumes.values()));
        }
        if (path.startsWith("/1.0/storage-pools/default-pool/volumes/custom/")) {
            String name = path.substring("/1.0/storage-pools/default-pool/volumes/custom/".length());
            if ("GET".equals(method)) {
                Map<String, Object> volume = this.customVolumes.get(name);
                return volume == null ? notFound("Storage volume not found") : sync(volume);
            }
            if ("DELETE".equals(method)) {
                return this.customVolumes.remove(name) == null
                    ? notFound("Storage volume not found") : sync(Map.of());
            }
        }
        if ("POST".equals(method) && path.equals("/1.0/storage-pools/default-pool/volumes/custom")) {
            Map<String, Object> body = new LinkedHashMap<>(parse(jsonBody));
            body.putIfAbsent("content_type", "block");
            this.customVolumes.put(String.valueOf(body.get("name")), body);
            return sync(body);
        }
        if ("POST".equals(method) && path.equals("/1.0/images")) {
            // The publish lane: source type=instance + an alias list. The alias resolves
            // afterwards, exactly like a real daemon's post-publish store.
            Map<String, Object> body = parse(jsonBody);
            String alias = body.get("aliases") instanceof List<?> aliases && !aliases.isEmpty()
                && aliases.get(0) instanceof Map<?, ?> first
                ? String.valueOf(first.get("name")) : null;
            if (alias != null) {
                this.lastPublishedAlias = alias;
                this.imageAliases.put(alias, "fp-published-" + alias);
            }
            return async();
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
            // MERGE, never replace: a real daemon's PUT rewrites the MUTABLE definition
            // (config, devices, profiles, description) and leaves the immutable identity
            // -- notably `type` -- exactly where it was. Replacing the stored map dropped
            // it, so the SECOND converge of one instance read type=null and the driver's
            // flavour guard refused a workload it had itself just written.
            Map<String, Object> body = parse(jsonBody);
            String name = path.substring("/1.0/instances/".length());
            Map<String, Object> stored = new LinkedHashMap<>(
                this.instances.getOrDefault(name, Map.of()));
            stored.putAll(body);
            this.instances.put(name, stored);
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
        // The ISO import lane: a streamed POST onto the custom-volume collection with
        // the name/type headers, exactly the wire shape IncusClient.importIsoVolume
        // sends. Anything else stays a loud gap.
        if ("POST".equals(method)
                && pathAndQuery.equals("/1.0/storage-pools/default-pool/volumes/custom")
                && extraHeaders != null && "iso".equals(extraHeaders.get("X-Incus-Type"))) {
            String name = extraHeaders.get("X-Incus-Name");
            Map<String, Object> volume = new LinkedHashMap<>();
            volume.put("name", name);
            volume.put("content_type", "iso");
            this.customVolumes.put(name, volume);
            return async();
        }
        throw new UnsupportedOperationException("not exercised by this journey: "
            + method + " " + pathAndQuery);
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

