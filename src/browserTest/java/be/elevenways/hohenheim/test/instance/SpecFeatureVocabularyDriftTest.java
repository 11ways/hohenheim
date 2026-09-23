package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusTransport;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.HealthCheck;
import be.elevenways.hohenheim.server.runtime.ImageOrigin;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.SpecFeature;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.util.Http11;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every optional {@link InstanceSpec} capability is JUDGED by every driver: either the
 * driver declares it in its {@code FEATURES} set, or its {@code create} refuses a spec that
 * requests it BY NAME before touching its daemon.
 *
 * AIDEV-NOTE: this is the drift binding of {@link SpecFeature}. {@link #specRequesting} is
 * an exhaustive switch EXPRESSION, so a new member does not compile until the test can
 * build a spec that asks for it -- and from then on both drivers either claim it or refuse
 * it, because the shared check asks every member. A spec component added WITHOUT a member
 * is the one shape this cannot see; that is what the member list's docblock warns about.
 */
class SpecFeatureVocabularyDriftTest {

    /** Valid owner labels without a controller database behind them. */
    private static final Map<String, String> LABELS = Map.of(
        OwnerLabels.MODEL, "hohenheim:instance", OwnerLabels.ID, "9",
        OwnerLabels.CONTROLLER, "drifttest");

    private static InstanceSpec.Builder base() {
        return InstanceSpec.builder("hohenheim-drifttest-instance-9", "alpine:latest",
            ResourceLimits.none(), ContainerHardening.STRICT, LABELS);
    }

    private static InstanceSpec specRequesting(SpecFeature feature) {
        return (switch (feature) {
            case CLOUD_INIT -> base().cloudInitUserData("#cloud-config\n");
            case ROOT_DISK_SIZE -> base().rootDiskGb(20);
            case BANDWIDTH_LIMIT -> base().networkLimitMbit(100);
            case PREPARED_IMAGE -> base().imageOrigin(ImageOrigin.PREPARED);
            case INSTALL_MEDIA -> base().imageOrigin(ImageOrigin.INSTALL_MEDIA);
            case PSEUDO_TERMINAL -> base().tty(true);
            case TMPFS_MOUNTS -> base().tmpfs(Map.of("/scratch", 1024L));
            case HEALTH_CHECK -> base().healthCheck(new HealthCheck("true", 10, 5, 3, 0));
            case WORKDIR -> base().workdir("/srv");
            case RUN_USER -> base().runUser(200_009);
        }).build();
    }

    @Test
    void everySpecFeatureIsDeliveredOrRefusedByNameByEveryDriver() {
        AtomicInteger daemonCalls = new AtomicInteger();
        InstanceRuntime docker = new DockerInstanceRuntime(
            new DockerClient(new CountingDockerTransport(daemonCalls)),
            new WorkloadNetworkPolicy((args, stdin) -> {
                throw new AssertionError("nft must never run for a refused-before-daemon spec");
            }, () -> true));
        InstanceRuntime incus = new IncusInstanceRuntime(
            new IncusClient(new CountingIncusTransport(daemonCalls)));
        Map<String, Set<SpecFeature>> drivers = Map.of(
            "docker", DockerInstanceRuntime.FEATURES, "incus", IncusInstanceRuntime.FEATURES);
        Map<String, InstanceRuntime> runtimes = Map.of("docker", docker, "incus", incus);

        // 1. A spec that asks for nothing requests nothing (the baseline the others differ from).
        assertThat(SpecFeature.allRequestedBy(base().build()))
            .as("step 1: a plain spec requests no optional feature").isEmpty();

        Set<SpecFeature> claimed = EnumSet.noneOf(SpecFeature.class);
        for (SpecFeature feature : SpecFeature.values()) {
            InstanceSpec spec = specRequesting(feature);

            // 2. The member recognises its own spec, and ONLY its own: a feature that also
            //    fired for another member's spec would be refused under the wrong name.
            assertThat(SpecFeature.allRequestedBy(spec))
                .as("step 2: the spec built for " + feature + " requests exactly it")
                .containsExactly(feature);

            // 3. Each driver either claims it or refuses it BY NAME before its daemon hears
            //    a single request.
            for (Map.Entry<String, Set<SpecFeature>> driver : drivers.entrySet()) {
                if (driver.getValue().contains(feature)) {
                    claimed.add(feature);
                    continue;
                }
                int before = daemonCalls.get();
                Throwable refused = catchThrowable(() ->
                    runtimes.get(driver.getKey()).create(spec));
                assertThat(refused)
                    .as("step 3: " + driver.getKey() + " refuses " + feature + " by name")
                    .isInstanceOf(IOException.class)
                    .hasMessage(feature.refusal(spec, driver.getKey()));
                assertThat(daemonCalls.get())
                    .as("step 3: and never asked its daemon anything first")
                    .isEqualTo(before);
            }
        }

        // 4. Every member is delivered by SOME driver today; a member nobody claims would be
        //    a capability the product offers and no host can honour.
        assertThat(claimed).as("step 4: every feature has a driver that delivers it")
            .containsExactlyInAnyOrder(SpecFeature.values());
    }

    /** A Docker transport that counts calls and answers none of them. */
    private static final class CountingDockerTransport implements DockerTransport {
        private final AtomicInteger calls;

        CountingDockerTransport(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
            this.calls.incrementAndGet();
            throw new IOException("drift test: the daemon must not be reached");
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes)
                throws IOException {
            return roundTrip(request, timeoutMs);
        }
    }

    /** An Incus transport that counts calls and answers none of them. */
    private static final class CountingIncusTransport implements IncusTransport {
        private final AtomicInteger calls;

        CountingIncusTransport(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody,
                                   long timeoutMs) throws IOException {
            this.calls.incrementAndGet();
            throw new IOException("drift test: the daemon must not be reached");
        }

        @Override
        public Http11.Raw exchangeUpload(String method, String pathAndQuery, Path bodyFile,
                                         String contentType, Map<String, String> extraHeaders,
                                         long timeoutMs) throws IOException {
            return exchange(method, pathAndQuery, null, timeoutMs);
        }

        @Override
        public Http11.Raw exchangeDownload(String method, String pathAndQuery,
                                           Path destination, long maxBytes, long timeoutMs)
                throws IOException {
            return exchange(method, pathAndQuery, null, timeoutMs);
        }

        @Override
        public IncusWebSocket openWebSocket(String pathAndQuery, long connectTimeoutMs)
                throws IOException {
            this.calls.incrementAndGet();
            throw new IOException("drift test: the daemon must not be reached");
        }

        @Override
        public String describe() {
            return "drift-test";
        }
    }
}
