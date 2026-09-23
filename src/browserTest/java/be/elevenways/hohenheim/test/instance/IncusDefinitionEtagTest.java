package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusTransport;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An Incus definition write is a CONDITIONAL read-modify-write: it carries the ETag of the
 * read it was built from, and a concurrent writer is re-read and re-applied instead of
 * being silently reverted.
 *
 * AIDEV-NOTE: the defect. Every device and config write read the whole definition and PUT
 * it back unconditionally, so an operator's {@code incus config device add} (or a parallel
 * converge) landing between the read and the PUT was REVERTED without a trace -- Incus's
 * PUT replaces the whole mutable definition. The counterfactual in step 4 performs exactly
 * that blind write against the same daemon and shows the operator's device vanish.
 */
class IncusDefinitionEtagTest {

    private static final String HANDLE = "hohenheim-etagtest-instance-3";

    @Test
    void aConcurrentWriterIsReReadAndKeptNeverSilentlyReverted() throws IOException {
        VersionedDaemon daemon = new VersionedDaemon();
        IncusInstanceRuntime runtime = new IncusInstanceRuntime(new IncusClient(daemon));
        InstanceSpec spec = InstanceSpec.builder(HANDLE, "images:debian/12",
            ResourceLimits.none(), ContainerHardening.STRICT, Map.of()).build();

        // 1. The operator adds a device while our removal is between its read and its PUT.
        daemon.concurrentEditOnNextPut = true;
        runtime.removeDevice(spec, "scratch", false);

        // 2. The first PUT carried the ETag of its read and was REFUSED (412); the edit was
        //    re-applied to a fresh read and the second PUT carried THAT read's ETag.
        assertThat(daemon.ifMatchSent).as("step 2: both PUTs were conditional, on their reads")
            .containsExactly("\"v1\"", "\"v2\"");
        assertThat(daemon.statuses).as("step 2: the stale write was refused, the fresh one kept")
            .containsExactly(412, 202);

        // 3. Both intents survive: our device is gone AND the operator's device is still there.
        assertThat(daemon.devices().keySet())
            .as("step 3: the removed device is gone and the concurrent one is kept")
            .containsExactlyInAnyOrder("eth0", "operator-disk");

        // 4. COUNTERFACTUAL: the unconditional write this replaced, built from a read taken
        //    BEFORE the operator's edit, silently reverts that edit.
        Map<String, Object> stale = new IncusClient(daemon).instance(HANDLE);
        daemon.addDevice("second-operator-disk");
        new IncusClient(daemon).updateInstance(HANDLE, stale);
        assertThat(daemon.devices().keySet())
            .as("step 4: without the ETag, the concurrent device is lost -- the defect")
            .doesNotContain("second-operator-disk");
    }

    /** One instance whose every write bumps an ETag, and which honours If-Match. */
    private static final class VersionedDaemon implements IncusTransport {

        private Map<String, Object> instance = initial();
        private int version = 1;
        private int operations;
        boolean concurrentEditOnNextPut;
        final List<String> ifMatchSent = new ArrayList<>();
        final List<Integer> statuses = new ArrayList<>();

        private static Map<String, Object> initial() {
            Map<String, Object> devices = new LinkedHashMap<>();
            devices.put("eth0", Map.of("type", "nic", "network", "incusbr0"));
            devices.put("scratch", Map.of("type", "disk", "pool", "p", "source", "v"));
            Map<String, Object> instance = new LinkedHashMap<>();
            instance.put("name", HANDLE);
            instance.put("type", "container");
            instance.put("architecture", "x86_64");
            instance.put("config", new LinkedHashMap<>(Map.of("user.note", "kept")));
            instance.put("devices", devices);
            instance.put("profiles", List.of("default"));
            instance.put("ephemeral", false);
            instance.put("description", "");
            return instance;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> devices() {
            return (Map<String, Object>) this.instance.get("devices");
        }

        void addDevice(String name) {
            Map<String, Object> devices = new LinkedHashMap<>(devices());
            devices.put(name, Map.of("type", "disk", "pool", "p", "source", name));
            this.instance.put("devices", devices);
            this.version++;
        }

        private String etag() {
            return "\"v" + this.version + "\"";
        }

        @Override
        public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody,
                                   long timeoutMs) throws IOException {
            return exchange(method, pathAndQuery, jsonBody, Map.of(), timeoutMs);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody,
                                   Map<String, String> headers, long timeoutMs)
                throws IOException {
            String path = pathAndQuery.contains("?")
                ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
            if ("GET".equals(method) && path.equals("/1.0/instances/" + HANDLE)) {
                return envelope(200, Map.of("type", "sync", "metadata", this.instance),
                    Map.of("etag", etag()));
            }
            if ("PUT".equals(method) && path.equals("/1.0/instances/" + HANDLE)) {
                String ifMatch = headers.get("If-Match");
                if (ifMatch != null) {
                    this.ifMatchSent.add(ifMatch);
                }
                if (this.concurrentEditOnNextPut) {
                    this.concurrentEditOnNextPut = false;
                    addDevice("operator-disk");
                }
                if (ifMatch != null && !ifMatch.equals(etag())) {
                    this.statuses.add(412);
                    return envelope(412, Map.of("type", "error", "error_code", 412,
                        "error", "ETag doesn't match"), Map.of());
                }
                Map<String, Object> body = (Map<String, Object>) new Dry().parse(jsonBody);
                Map<String, Object> updated = new LinkedHashMap<>(this.instance);
                updated.putAll(body);
                this.instance = updated;
                this.version++;
                this.statuses.add(202);
                return envelope(202, Map.of("type", "async",
                    "operation", "/1.0/operations/op-" + (++this.operations),
                    "metadata", Map.of()), Map.of());
            }
            if ("GET".equals(method) && path.matches("/1\\.0/operations/.+/wait")) {
                return envelope(200, Map.of("type", "sync", "metadata",
                    Map.of("status_code", 200, "status", "Success", "description", "put",
                        "err", "")), Map.of());
            }
            throw new IOException("VersionedDaemon: unhandled " + method + " " + path);
        }

        private static Http11.Raw envelope(int status, Map<String, Object> body,
                                           Map<String, String> headers) {
            return new Http11.Raw(status, headers,
                Json.stringify(body).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Http11.Raw exchangeUpload(String method, String pathAndQuery, Path bodyFile,
                                         String contentType, Map<String, String> extraHeaders,
                                         long timeoutMs) {
            throw new UnsupportedOperationException("not exercised");
        }

        @Override
        public Http11.Raw exchangeDownload(String method, String pathAndQuery,
                                           Path destination, long maxBytes, long timeoutMs) {
            throw new UnsupportedOperationException("not exercised");
        }

        @Override
        public IncusWebSocket openWebSocket(String pathAndQuery, long connectTimeoutMs) {
            throw new UnsupportedOperationException("not exercised");
        }

        @Override
        public String describe() {
            return "versioned-daemon";
        }
    }
}
