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
import java.util.ArrayList;
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

    /** The default pool's total size; a restore's capacity probe reads it (the pool reports nothing used). */
    volatile long poolTotalBytes = 100L * 1024 * 1024 * 1024;

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
        // marker beside the isolation ACL, and both ride the same echo store. used_by is
        // DERIVED from the stored instances' NICs on every read, as a real daemon reports
        // it, because ControllerPresence.usedBy reads a MISSING field as "referenced" on
        // purpose and a release decides on exactly this field.
        if ("GET".equals(method) && path.equals("/1.0/network-acls")) {
            return sync(withUsedBy(this.aclStore, true));
        }
        if ("GET".equals(method) && path.startsWith("/1.0/network-acls/")) {
            String name = path.substring("/1.0/network-acls/".length());
            Object stored = this.aclStore.get(name);
            return stored == null ? notFound("Network ACL not found") : sync(withUsedBy(name, stored, true));
        }
        if ("POST".equals(method) && path.equals("/1.0/network-acls")) {
            Map<String, Object> body = new LinkedHashMap<>(parse(jsonBody));
            this.aclStore.put(String.valueOf(body.get("name")), body);
            return sync(body);
        }
        if ("PUT".equals(method) && path.startsWith("/1.0/network-acls/")) {
            Map<String, Object> body = new LinkedHashMap<>(parse(jsonBody));
            this.aclStore.put(path.substring("/1.0/network-acls/".length()), body);
            return sync(body);
        }
        if ("DELETE".equals(method) && path.startsWith("/1.0/network-acls/")) {
            // The daemon's own guard: an ACL a NIC references is refused, never removed.
            String name = path.substring("/1.0/network-acls/".length());
            if (!this.aclStore.containsKey(name)) {
                return notFound("Network ACL not found");
            }
            if (!usedBy(name, true).isEmpty()) {
                return error(400, "Cannot delete an ACL that is in use");
            }
            this.aclStore.remove(name);
            return sync(Map.of());
        }
        // The managed EXTRA-NIC bridge. A real daemon auto-assigns subnets and NAT for
        // an empty config and reports the result as managed, which is exactly what
        // ensureExtraNetwork read-back-verifies -- so the fake answers with a plausible
        // assignment rather than echoing the empty config it was sent, which would fail
        // the verification on the FAKE instead of on anything the driver did.
        if ("GET".equals(method) && path.equals("/1.0/networks")) {
            return sync(withUsedBy(this.networkStore, false));
        }
        if ("GET".equals(method) && path.startsWith("/1.0/networks/")) {
            String name = path.substring("/1.0/networks/".length());
            Object stored = this.networkStore.get(name);
            return stored == null ? notFound("Network not found") : sync(withUsedBy(name, stored, false));
        }
        if ("DELETE".equals(method) && path.startsWith("/1.0/networks/")) {
            String name = path.substring("/1.0/networks/".length());
            if (!this.networkStore.containsKey(name)) {
                return notFound("Network not found");
            }
            if (!usedBy(name, false).isEmpty()) {
                return error(400, "The network is currently in use");
            }
            this.networkStore.remove(name);
            return sync(Map.of());
        }
        if ("POST".equals(method) && path.equals("/1.0/networks")) {
            Map<String, Object> body = new LinkedHashMap<>(parse(jsonBody));
            body.put("managed", true);
            body.put("config", Map.of("ipv4.address", "10.181.7.1/24", "ipv4.nat", "true"));
            this.networkStore.put(String.valueOf(body.get("name")), body);
            return sync(body);
        }
        if ("GET".equals(method) && path.equals("/1.0/storage-pools/default-pool/resources")) {
            // A roomy pool: a restore's capacity probe reads it before anything is created.
            return sync(Map.of("space", Map.of("total", this.poolTotalBytes, "used", 0L)));
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
        if ("DELETE".equals(method) && path.startsWith("/1.0/instances/")
                && path.indexOf('/', "/1.0/instances/".length()) < 0) {
            // A destroy's delete: an absent workload answers 404 like a real daemon, which the driver reads as gone.
            return this.instances.remove(path.substring("/1.0/instances/".length())) == null
                ? notFound("Instance not found") : async();
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
        return error(404, message);
    }

    private static Http11.Raw error(int status, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "error");
        envelope.put("error_code", status);
        envelope.put("error", message);
        return raw(status, Json.stringify(envelope));
    }

    /** Every stored object of one store as the recursed listing answers it, each with its derived used_by. */
    private List<Object> withUsedBy(Map<String, Object> store, boolean acl) {
        return store.entrySet().stream().map(entry -> withUsedBy(entry.getKey(), entry.getValue(), acl)).toList();
    }

    @SuppressWarnings("unchecked")
    private Object withUsedBy(String name, Object stored, boolean acl) {
        Map<String, Object> read = new LinkedHashMap<>((Map<String, Object>) stored);
        read.put("used_by", usedBy(name, acl));
        return read;
    }

    /** The instances whose NICs name this ACL (security.acls) or sit on this network, as the daemon lists them. */
    private List<String> usedBy(String name, boolean acl) {
        List<String> users = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> instance : this.instances.entrySet()) {
            if (!(instance.getValue().get("devices") instanceof Map<?, ?> devices)) {
                continue;
            }
            for (Object device : devices.values()) {
                if (!(device instanceof Map<?, ?> nic) || !"nic".equals(nic.get("type"))) {
                    continue;
                }
                boolean uses = acl
                    ? nic.get("security.acls") instanceof String acls && List.of(acls.split(",")).contains(name)
                    : name.equals(nic.get("network"));
                if (uses) {
                    users.add("/1.0/instances/" + instance.getKey());
                    break;
                }
            }
        }
        return users;
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

