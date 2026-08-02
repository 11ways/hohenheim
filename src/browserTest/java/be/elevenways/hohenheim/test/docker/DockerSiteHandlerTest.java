package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
        } finally {
            handler.destroy();
            try {
                docker.removeVolume(volumeName, true);
            } catch (IOException ignored) {
                // best effort
            }
        }

        assertThat(handler.getHealth()).isEqualTo(SiteHealth.DOWN);
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
