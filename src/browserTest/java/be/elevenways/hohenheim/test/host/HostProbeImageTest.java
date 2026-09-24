package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusTransport;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Incus kernel probe runs the declared probe image and records which build it ran, against
 * a scripted daemon: the Incus twin of the Docker battery's pinned-image assertion.
 *
 * AIDEV-NOTE: deliberately not named *Incus*: that glob routes a class to the non-hermetic lane,
 * and this one needs no daemon at all.
 */
class HostProbeImageTest {

    private static final String FINGERPRINT = "0eed34fbcb0643ae1a08546f904a3329d75e7690638a582371edd3268a8b4e31";

    @Test
    void theKernelProbeRunsThePinnedAliasAndRecordsTheBuildItRan() throws Exception {
        // 1. The declared reference is the release AND variant alias, never the bare release.
        assertThat(IncusPreflight.PROBE_IMAGE)
            .as("step 1: the probe names the variant-explicit alias")
            .isEqualTo("alpine/3.22/default");

        // 2. A daemon that resolves the alias and runs the probe: the create names exactly the
        //    declared alias on the declared image server, and the build it resolved is recorded.
        ScriptedDaemon daemon = new ScriptedDaemon(false);
        IncusPreflight.KernelProbe probe =
            IncusPreflight.probeInstanceKernel(new IncusClient(daemon), true);
        assertThat(daemon.createdSource)
            .as("step 2: the probe instance is created from the pinned alias")
            .containsEntry("alias", IncusPreflight.PROBE_IMAGE)
            .containsEntry("server", IncusInstanceRuntime.IMAGE_SERVER)
            .containsEntry("protocol", "simplestreams");
        assertThat(probe.imageFingerprint())
            .as("step 2: the build the daemon resolved is carried out of the probe")
            .isEqualTo(FINGERPRINT);
        assertThat(probe.checks())
            .as("step 2: and the probe still produced its verdicts from the instance's /proc")
            .extracting(HostPreflight.Check::name)
            .containsExactly(IncusPreflight.USERNS_CHECK, IncusPreflight.SECCOMP_CHECK, "lsm");
        assertThat(probe.checks().get(0).status())
            .as("step 2: a remapped uid_map passes")
            .isEqualTo(HostPreflight.STATUS_PASS);
        assertThat(daemon.deleted)
            .as("step 2: the probe instance is removed afterwards")
            .isTrue();

        // 3. A daemon that cannot obtain the image (an air-gapped host, a pruned build): every
        //    verdict is UNKNOWN, and the detail names the exact image the operator must provide.
        ScriptedDaemon refusing = new ScriptedDaemon(true);
        IncusPreflight.KernelProbe refused =
            IncusPreflight.probeInstanceKernel(new IncusClient(refusing), true);
        assertThat(refused.imageFingerprint())
            .as("step 3: nothing ran, so no build is claimed")
            .isNull();
        assertThat(refused.checks())
            .as("step 3: every verdict fails as UNKNOWN, naming the image and its server")
            .allSatisfy(check -> {
                assertThat(check.status()).isEqualTo(HostPreflight.STATUS_FAIL);
                assertThat(check.detail())
                    .contains("UNKNOWN")
                    .contains(IncusPreflight.PROBE_IMAGE)
                    .contains(IncusInstanceRuntime.IMAGE_SERVER);
            });
    }

    /**
     * The handful of endpoints the kernel probe touches, answered the way a daemon does: the
     * created instance carries {@code volatile.base_image}, and the one exec returns a remapped
     * uid_map, a filtering seccomp mode and no LSM label.
     */
    private static final class ScriptedDaemon implements IncusTransport {

        private static final String STDOUT = "/1.0/instances/probe/logs/exec-output/stdout.log";

        private final boolean refuseImage;
        private final Map<String, Object> instance = new LinkedHashMap<>();
        private Map<String, Object> createdSource;
        private boolean deleted;
        private int operations;
        private String execOperation;

        ScriptedDaemon(boolean refuseImage) {
            this.refuseImage = refuseImage;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody,
                                   long timeoutMs) throws IOException {
            String path = pathAndQuery.contains("?")
                ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
            if ("POST".equals(method) && path.equals("/1.0/instances")) {
                Map<String, Object> body = parse(jsonBody);
                this.createdSource = (Map<String, Object>) body.get("source");
                if (this.refuseImage) {
                    return error(404, "Failed getting remote image info: Image not found");
                }
                this.instance.putAll(body);
                Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) body.get("config"));
                config.put("volatile.base_image", FINGERPRINT);
                this.instance.put("config", config);
                return async();
            }
            if ("GET".equals(method) && path.startsWith("/1.0/instances/")
                    && !path.contains("/logs/")) {
                return this.instance.isEmpty() ? error(404, "Instance not found") : sync(this.instance);
            }
            if ("PUT".equals(method) && path.endsWith("/state")) {
                return this.instance.isEmpty() ? error(404, "Instance not found") : async();
            }
            if ("POST".equals(method) && path.endsWith("/exec")) {
                String operation = nextOperation();
                this.execOperation = operation;
                return asyncAt(operation);
            }
            if ("GET".equals(method) && path.endsWith("/wait")) {
                Map<String, Object> finished = new LinkedHashMap<>();
                finished.put("status_code", 200);
                finished.put("status", "Success");
                if (path.equals(this.execOperation + "/wait")) {
                    finished.put("metadata", Map.of("return", 0, "output", Map.of("1", STDOUT)));
                }
                return sync(finished);
            }
            if ("GET".equals(method) && path.equals(STDOUT)) {
                String output = "0 1000000 1000000000\n---\nSeccomp:\t2\nNoNewPrivs:\t0\n---\n";
                return new Http11.Raw(200, Map.of(), output.getBytes(StandardCharsets.UTF_8));
            }
            if ("DELETE".equals(method) && path.equals(STDOUT)) {
                return sync(Map.of());
            }
            if ("DELETE".equals(method) && path.startsWith("/1.0/instances/")) {
                if (this.instance.isEmpty()) {
                    return error(404, "Instance not found");
                }
                this.deleted = true;
                this.instance.clear();
                return async();
            }
            throw new IOException("ScriptedDaemon: unhandled " + method + " " + path);
        }

        private String nextOperation() {
            return "/1.0/operations/op-" + (++this.operations);
        }

        private Http11.Raw async() {
            return asyncAt(nextOperation());
        }

        private static Http11.Raw asyncAt(String operation) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "async");
            envelope.put("operation", operation);
            envelope.put("metadata", Map.of());
            return raw(202, envelope);
        }

        private static Http11.Raw sync(Object metadata) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "sync");
            envelope.put("metadata", metadata);
            return raw(200, envelope);
        }

        private static Http11.Raw error(int code, String message) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "error");
            envelope.put("error_code", code);
            envelope.put("error", message);
            return raw(code, envelope);
        }

        private static Http11.Raw raw(int status, Map<String, Object> envelope) {
            return new Http11.Raw(status, Map.of(),
                Json.stringify(envelope).getBytes(StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> parse(String json) throws IOException {
            try {
                return (Map<String, Object>) new Dry().parse(json);
            } catch (Exception malformed) {
                throw new IOException("ScriptedDaemon: bad JSON body: " + json, malformed);
            }
        }

        @Override
        public Http11.Raw exchangeUpload(String method, String pathAndQuery, Path bodyFile,
                                         String contentType, Map<String, String> extraHeaders,
                                         long timeoutMs) throws IOException {
            throw new IOException("ScriptedDaemon: the kernel probe uploads nothing");
        }

        @Override
        public Http11.Raw exchangeDownload(String method, String pathAndQuery, Path destination,
                                           long maxBytes, long timeoutMs) throws IOException {
            throw new IOException("ScriptedDaemon: the kernel probe downloads nothing");
        }

        @Override
        public IncusWebSocket openWebSocket(String pathAndQuery, long connectTimeoutMs)
                throws IOException {
            throw new IOException("ScriptedDaemon: the kernel probe opens no websocket");
        }

        @Override
        public String describe() {
            return "scripted incus daemon";
        }
    }
}
