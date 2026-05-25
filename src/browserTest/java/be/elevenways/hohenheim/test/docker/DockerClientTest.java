package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.DockerClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link DockerClient} against a real Docker daemon.
 * Each test is skipped when the daemon socket is absent (e.g. CI without Docker).
 */
class DockerClientTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    @Test
    void pingReturnsTrueWhenDaemonReachable() {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assertThat(new DockerClient().ping()).isTrue();
    }

    @Test
    void versionReportsApiVersion() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        Map<String, Object> version = new DockerClient().version();
        assertThat(version).containsKey("ApiVersion");
        assertThat((String) version.get("Version")).isNotBlank();
    }

    @Test
    void listContainersParsesIntoMaps() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        List<Object> containers = new DockerClient().listContainers(true);
        assertThat(containers).isNotNull();
        for (Object container : containers) {
            assertThat(container).isInstanceOf(Map.class);
        }
    }
}
