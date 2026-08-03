package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link DockerSiteRequestHandler} against a real Docker daemon.
 * Skipped when the daemon socket or the test image is absent.
 */
class DockerSiteHandlerTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";
    private static final int TEST_SITE_ID = 999_001;
    private static final String CONTAINER_NAME = "hohenheim-site-" + TEST_SITE_ID;

    // The handler records its published port in the port ledger, so every start here
    // needs a datasource -- a claim that cannot be written is a failure, not a shrug.
    @BeforeAll
    static void bootRuntime() {
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsContainerPublishesPortAndReportsHealth() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        // A long-lived command keeps the container running; the port need not be
        // listened on for Docker to publish the host->container binding. The volume
        // exercises birth-labelling of the one unrecoverable resource kind.
        String volumeName = "hohenheim-site-" + TEST_SITE_ID + "-vol-data";
        DockerSiteRequestHandler handler = new DockerSiteRequestHandler(TEST_SITE_ID, Map.of(
            "image", "alpine",
            "tag", "latest",
            "container_port", 8080,
            "command", "sleep 3600",
            "volumes", Map.of("data", "/data")
        ));

        try {
            assertThat(handler.getHealth()).isEqualTo(SiteHealth.UP);

            URI upstream = handler.getUpstream();
            assertThat(upstream).isNotNull();
            assertThat(upstream.getScheme()).isEqualTo("http");
            assertThat(upstream.getHost()).isEqualTo("127.0.0.1");
            assertThat(upstream.getPort()).isGreaterThan(0);

            Map<String, Object> info = docker.inspectContainer(CONTAINER_NAME);
            Map<String, Object> state = (Map<String, Object>) info.get("State");
            assertThat(state.get("Running")).isEqualTo(Boolean.TRUE);

            // The container was born carrying its owner claim...
            Map<String, Object> config = (Map<String, Object>) info.get("Config");
            OwnerLabels.Owner containerOwner =
                OwnerLabels.parse((Map<?, ?>) config.get("Labels"));
            assertThat(containerOwner).as("container carries owner labels").isNotNull();
            assertThat(containerOwner.model()).isEqualTo(SiteModel.MODEL_ID);
            assertThat(containerOwner.id()).isEqualTo(String.valueOf(TEST_SITE_ID));

            // ...and so was the volume, the resource that never gets relabelled.
            Map<String, Object> volume = docker.inspectVolume(volumeName);
            OwnerLabels.Owner volumeOwner =
                OwnerLabels.parse((Map<?, ?>) volume.get("Labels"));
            assertThat(volumeOwner).as("volume carries owner labels from birth").isNotNull();
            assertThat(volumeOwner.model()).isEqualTo(SiteModel.MODEL_ID);
            assertThat(volumeOwner.id()).isEqualTo(String.valueOf(TEST_SITE_ID));

            // Record-after: the port the KERNEL picked is in the ledger, owned by the
            // site record and therefore visible to every other port authority.
            String key = PortLedger.claimKeyOf(ServerModel.localServerId(),
                "127.0.0.1", upstream.getPort(), "tcp");
            Row claim = PortLedger.holderOf(key);
            assertThat(claim).as("the published port is claimed in the ledger").isNotNull();
            assertThat(PortLedger.isOwnedBy(claim, SiteModel.MODEL_ID, TEST_SITE_ID))
                .as("the claim names the site record as owner").isTrue();
            assertThatThrownBy(() -> PortLedger.claim(ServerModel.localServerId(), "0.0.0.0",
                    upstream.getPort(), "tcp", null, null, "a rival authority"))
                .as("another authority cannot double-book the same host port")
                .isInstanceOf(PortLedger.PortConflict.class);
        } finally {
            handler.destroy();
            try {
                docker.removeVolume(volumeName, true);
            } catch (IOException ignored) {
                // best effort
            }
        }

        assertThat(handler.getHealth()).isEqualTo(SiteHealth.DOWN);
        // The teardown gave the port back: no claim survives the container.
        assertThat(PortLedger.claimsOf(SiteModel.MODEL_ID, TEST_SITE_ID))
            .as("destroying the handler released the site's port claim").isEmpty();
        // After destroy the container is gone: inspect must fail.
        try {
            docker.inspectContainer(CONTAINER_NAME);
            throw new AssertionError("expected inspect of removed container to fail");
        } catch (IOException expected) {
            // 404 from the daemon -> IOException, as intended
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void volumesAndResourceLimitsReachTheContainer() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        int siteId = 999_003;
        String volumeName = "hohenheim-site-" + siteId + "-vol-data";
        DockerSiteRequestHandler handler = new DockerSiteRequestHandler(siteId, Map.of(
            "image", "alpine",
            "tag", "latest",
            "container_port", 8080,
            "command", "sleep 3600",
            "volumes", Map.of("data", "/data"),
            "memory_limit_mb", 128,
            "cpu_limit", 0.5
        ));

        try {
            Map<String, Object> info = docker.inspectContainer("hohenheim-site-" + siteId);
            Map<String, Object> hostConfig = (Map<String, Object>) info.get("HostConfig");
            assertThat(((Number) hostConfig.get("Memory")).longValue()).isEqualTo(128L * 1024 * 1024);
            assertThat(((Number) hostConfig.get("NanoCpus")).longValue()).isEqualTo(500_000_000L);

            List<?> mounts = (List<?>) info.get("Mounts");
            boolean mounted = mounts.stream().anyMatch(mount ->
                mount instanceof Map<?, ?> m
                    && volumeName.equals(m.get("Name"))
                    && "/data".equals(m.get("Destination")));
            assertThat(mounted).as("named volume mounted at /data").isTrue();
        } finally {
            handler.destroy();
            try {
                docker.removeVolume(volumeName, true);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    /**
     * A same-named container the daemon cannot attribute to this site is never
     * force-removed: the site stays DOWN (a loud, visible refusal) and the foreign
     * container survives on the host.
     */
    @Test
    void refusesToReplaceAForeignSameNamedContainer() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        int siteId = 999_004;
        String containerName = "hohenheim-site-" + siteId;
        // 1. Plant an UNLABELLED squatter on the site's container name.
        docker.createContainer(containerName, Map.of(
            "Image", "alpine:latest", "Cmd", List.of("sleep", "300")));
        try {
            // 2. The handler refuses the replace and stays down instead of destroying it.
            DockerSiteRequestHandler handler = new DockerSiteRequestHandler(siteId, Map.of(
                "image", "alpine",
                "tag", "latest",
                "container_port", 8080,
                "command", "sleep 3600"
            ));
            assertThat(handler.getHealth())
                .as("step 2: the site stays DOWN rather than stealing the name")
                .isEqualTo(SiteHealth.DOWN);
            assertThat(handler.getUpstream())
                .as("step 2: no upstream was resolved").isNull();
            // 3. HOST state: the foreign container survives, unlabelled and unharmed.
            assertThat(docker.inspectContainer(containerName))
                .as("step 3: the foreign container still exists").isNotNull();
        } finally {
            docker.removeContainer(containerName, true);
        }
    }

    @Test
    void buildsImageFromContextAndRuns() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " (build base) not present");

        int siteId = 999_002;
        String builtImage = "hohenheim-site-" + siteId + ":latest";
        Path context = Files.createTempDirectory("hohenheim-docker-build");
        try {
            // A git-provisioned docker site builds from its checkout's Dockerfile; here
            // build_context stands in for the checkout dir GitDeployment would inject.
            Files.writeString(context.resolve("Dockerfile"),
                "FROM alpine:latest\nCMD [\"sleep\", \"3600\"]\n");

            DockerSiteRequestHandler handler = new DockerSiteRequestHandler(siteId, Map.of(
                "build_context", context.toString(),
                "container_port", 8080
            ));
            try {
                assertThat(handler.getUpstream()).isNotNull();
                assertThat(handler.getHealth()).isEqualTo(SiteHealth.UP);
                assertThat(imagePresent(docker, builtImage)).isTrue();
            } finally {
                handler.destroy();
            }
        } finally {
            try {
                docker.removeImage(builtImage, true);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            Files.deleteIfExists(context.resolve("Dockerfile"));
            Files.deleteIfExists(context);
        }
    }

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
