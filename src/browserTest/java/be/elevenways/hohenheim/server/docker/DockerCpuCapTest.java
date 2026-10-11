package be.elevenways.hohenheim.server.docker;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container's CPU cap never exceeds the host's CPUs: Docker refuses such a cap, and it would throttle nothing.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DockerCpuCapTest {

    /** A daemon with {@code cpus} CPUs that records every create body it receives. */
    private static final class OneHostDaemon implements DockerTransport {

        private final int cpus;
        private final List<String> creates = new ArrayList<>();
        private int infoCalls;

        private OneHostDaemon(int cpus) {
            this.cpus = cpus;
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) {
            String text = new String(request, StandardCharsets.UTF_8);
            String firstLine = text.substring(0, text.indexOf("\r\n"));
            String body;
            if (firstLine.startsWith("GET /info")) {
                this.infoCalls++;
                body = "{\"NCPU\":" + this.cpus + "}";
            } else {
                this.creates.add(text.substring(text.indexOf("\r\n\r\n") + 4));
                body = "{\"Id\":\"c0ffee\"}";
            }
            return ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n" + body)
                .getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes) {
            return this.roundTrip(request, timeoutMs);
        }
    }

    @Test
    void aCpuCapAboveTheHostsCpusIsLoweredToThem() throws IOException {
        OneHostDaemon daemon = new OneHostDaemon(1);
        DockerClient docker = new DockerClient(daemon, 5_000);

        // 1. A build quota of two CPUs on a one-CPU host is sent as one CPU.
        docker.createContainer("build-1", spec(ResourceLimits.of(256, 2.0)), ContainerHardening.STRICT);
        assertThat(daemon.creates.get(0)).as("step 1: the cap is the host's one CPU")
            .contains("\"NanoCpus\":1000000000").doesNotContain("2000000000");

        // 2. A cap within the host is sent as asked, and the host's CPUs are read once.
        docker.createContainer("build-2", spec(ResourceLimits.of(256, 0.5)), ContainerHardening.STRICT);
        assertThat(daemon.creates.get(1)).as("step 2: half a CPU stays half a CPU").contains("\"NanoCpus\":500000000");
        assertThat(daemon.infoCalls).as("step 2: the daemon was asked once").isEqualTo(1);

        // 3. Without a CPU cap nothing is added and the daemon is not asked.
        OneHostDaemon uncapped = new OneHostDaemon(1);
        new DockerClient(uncapped, 5_000).createContainer("plain", spec(ResourceLimits.of(256, null)),
            ContainerHardening.STRICT);
        assertThat(uncapped.creates.get(0)).as("step 3: no cap, none sent").doesNotContain("NanoCpus");
        assertThat(uncapped.infoCalls).as("step 3: no question for the daemon").isZero();
    }

    private static Map<String, Object> spec(ResourceLimits limits) {
        Map<String, Object> hostConfig = new LinkedHashMap<>();
        limits.applyTo(hostConfig);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", "alpine");
        spec.put("HostConfig", hostConfig);
        return spec;
    }
}
