package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant containers may not log without a bound, driven at the WIRE: what
 * {@code DockerClient.createContainer} actually sends to the daemon.
 *
 * The attack this pins is availability, not confidentiality: with no rotation configured
 * every managed container inherits the daemon's json-file default, which is one file with
 * no maximum size, so a workload printing in a loop fills the host disk out from under
 * every other tenant. Asserting the funnel's OUTPUT rather than a helper's return value is
 * deliberate -- the claim is "no caller can ship a container without the cap", and only the
 * request body proves that.
 */
class ContainerLogLimitTest {

    @Test
    void everyCreatedContainerCarriesABoundedRotatingLogAndNoCallerCanRemoveIt()
            throws IOException {
        Integer previousSize = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.CONTAINER_LOG_MAX_SIZE_MB);
        Integer previousFiles = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Security.CONTAINER_LOG_MAX_FILES);
        RecordingTransport transport = new RecordingTransport();
        DockerClient docker = new DockerClient(transport);

        try {
            // 1. The plainest possible create -- a caller that declares NOTHING about
            //    logging, which is every caller in the tree. The bound must be on the wire
            //    anyway, because the funnel put it there.
            docker.createContainer("log-cap-probe", spec(), DockerContainerKind.HARDENING);
            assertThat(transport.lastCreateBody)
                .as("step 1: the create really did reach the daemon").isNotNull();
            assertThat(transport.lastCreateBody)
                .as("step 1: with a bounded rotating log, not the daemon's unlimited default")
                .contains("\"LogConfig\"")
                .contains("\"max-size\":\"10m\"")
                .contains("\"max-file\":\"3\"");
            assertThat(transport.lastCreateBody)
                .as("step 1: on the driver the console lane can actually read back")
                .contains("\"Type\":\"json-file\"");

            // 2. It is a POLICY, so it follows the operator's setting rather than a
            //    constant -- and the numbers on the wire are the numbers configured.
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.CONTAINER_LOG_MAX_SIZE_MB, 4);
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.CONTAINER_LOG_MAX_FILES, 2);
            docker.createContainer("log-cap-probe-tuned", spec(), DockerContainerKind.HARDENING);
            assertThat(transport.lastCreateBody)
                .as("step 2: the operator's own ceiling is what ships")
                .contains("\"max-size\":\"4m\"")
                .contains("\"max-file\":\"2\"");

            // 3. Nonsense is not obeyed: a zero or negative cap is not "unlimited", it
            //    falls back to the default. A setting that could switch the policy off
            //    would be the same hole wearing a form.
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.CONTAINER_LOG_MAX_SIZE_MB, 0);
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.CONTAINER_LOG_MAX_FILES, -1);
            docker.createContainer("log-cap-probe-zero", spec(), DockerContainerKind.HARDENING);
            assertThat(transport.lastCreateBody)
                .as("step 3: 0 or less is the DEFAULT, never 'no limit'")
                .contains("\"max-size\":\"" + ContainerHardening.DEFAULT_LOG_MAX_SIZE_MB + "m\"")
                .contains("\"max-file\":\"" + ContainerHardening.DEFAULT_LOG_MAX_FILES + "\"");

            // 4. And a caller cannot hand the unbounded default back: LogConfig is owned by
            //    the policy, so a spec carrying one is REFUSED rather than overwritten --
            //    the same stance as PidsLimit, and the reason this is a funnel at all.
            Map<String, Object> smuggled = spec();
            smuggled.put("HostConfig", new LinkedHashMap<>(Map.of(
                "LogConfig", Map.of("Type", "json-file", "Config", Map.of()))));
            transport.lastCreateBody = null;
            assertThatThrownBy(() -> docker.createContainer("log-cap-smuggled", smuggled,
                    DockerContainerKind.HARDENING))
                .as("step 4: a caller-supplied LogConfig is refused by name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LogConfig");
            assertThat(transport.lastCreateBody)
                .as("step 4: STATE, not just the throw -- nothing reached the daemon")
                .isNull();
        } finally {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.CONTAINER_LOG_MAX_SIZE_MB,
                previousSize == null ? ContainerHardening.DEFAULT_LOG_MAX_SIZE_MB : previousSize);
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Security.CONTAINER_LOG_MAX_FILES,
                previousFiles == null ? ContainerHardening.DEFAULT_LOG_MAX_FILES : previousFiles);
        }
    }

    private static Map<String, Object> spec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", "alpine:latest");
        return spec;
    }

    /** Answers every request with a minimal create result and keeps the body it was sent. */
    private static final class RecordingTransport implements DockerTransport {

        private @Nullable String lastCreateBody;

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) {
            return roundTrip(request, timeoutMs, Long.MAX_VALUE);
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes) {
            String text = new String(request, StandardCharsets.ISO_8859_1);
            int headEnd = text.indexOf("\r\n\r\n");
            if (text.startsWith("POST /containers/create") && headEnd >= 0) {
                this.lastCreateBody = text.substring(headEnd + 4);
            }
            String body = "{\"Id\":\"fake-container\"}";
            return ("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body)
                .getBytes(StandardCharsets.ISO_8859_1);
        }
    }
}
