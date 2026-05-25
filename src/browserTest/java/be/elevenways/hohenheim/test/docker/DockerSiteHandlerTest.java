package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
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
        // listened on for Docker to publish the host->container binding.
        DockerSiteRequestHandler handler = new DockerSiteRequestHandler(TEST_SITE_ID, Map.of(
            "image", "alpine",
            "tag", "latest",
            "container_port", 8080,
            "command", "sleep 3600"
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
        } finally {
            handler.destroy();
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
