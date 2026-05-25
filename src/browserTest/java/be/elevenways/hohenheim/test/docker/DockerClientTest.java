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
 * Daemon-dependent tests are skipped when the socket is absent (e.g. CI without Docker).
 */
class DockerClientTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";

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

    @Test
    void listImagesParsesIntoMaps() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        List<Object> images = new DockerClient().listImages();
        assertThat(images).isNotNull();
        for (Object image : images) {
            assertThat(image).isInstanceOf(Map.class);
        }
    }

    @Test
    void buildsImageFromDockerfile() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " (build base) not present");

        Path context = Files.createTempDirectory("hohenheim-build-test");
        String tag = "hohenheim-buildtest-" + System.nanoTime() + ":latest";
        try {
            Files.writeString(context.resolve("Dockerfile"),
                "FROM alpine:latest\nRUN echo hohenheim > /built.txt\n");

            docker.buildImage(context, tag, "Dockerfile");
            assertThat(imagePresent(docker, tag)).isTrue();
        } finally {
            try {
                docker.removeImage(tag, true);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            Files.deleteIfExists(context.resolve("Dockerfile"));
            Files.deleteIfExists(context);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void containerLifecycle() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        String name = "hohenheim-dockerclient-test-" + System.nanoTime();
        String id = docker.createContainer(name, Map.of(
            "Image", TEST_IMAGE,
            "Cmd", List.of("sleep", "30")
        ));
        assertThat(id).isNotBlank();

        try {
            docker.startContainer(id);

            Map<String, Object> info = docker.inspectContainer(id);
            assertThat((String) info.get("Id")).startsWith(id.substring(0, 12));
            Map<String, Object> state = (Map<String, Object>) info.get("State");
            assertThat(state.get("Running")).isEqualTo(Boolean.TRUE);

            docker.stopContainer(id, 1);   // PID-1 sleep ignores SIGTERM; SIGKILL after 1s
        } finally {
            docker.removeContainer(id, true);
        }

        // After force-removal the container is gone: inspect must fail.
        try {
            docker.inspectContainer(id);
            throw new AssertionError("expected inspect of removed container to fail");
        } catch (IOException expected) {
            // 404 from the daemon -> IOException, as intended
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void containerLogsDemuxesStdoutAndStderr() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        String name = "hohenheim-logs-test-" + System.nanoTime();
        String id = docker.createContainer(name, Map.of(
            "Image", TEST_IMAGE,
            "Cmd", List.of("sh", "-c", "echo hohenheim-out; echo hohenheim-err 1>&2")
        ));
        try {
            docker.startContainer(id);
            waitForExit(docker, id, 10_000);

            // Both frames (stdout=1, stderr=2) must be demultiplexed into the snapshot.
            String logs = docker.containerLogs(id, true, true, 0);
            assertThat(logs).contains("hohenheim-out");
            assertThat(logs).contains("hohenheim-err");
        } finally {
            docker.removeContainer(id, true);
        }
    }

    @SuppressWarnings("unchecked")
    private static void waitForExit(DockerClient docker, String id, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> state = (Map<String, Object>) docker.inspectContainer(id).get("State");
            if (Boolean.FALSE.equals(state.get("Running"))) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for container exit");
            }
        }
    }

    @Test
    void execCapturesOutputAndExitCode() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        String name = "hohenheim-exec-test-" + System.nanoTime();
        String id = docker.createContainer(name, Map.of(
            "Image", TEST_IMAGE,
            "Cmd", List.of("sleep", "30")   // long-lived so we can exec into it
        ));
        try {
            docker.startContainer(id);

            DockerClient.ExecResult result = docker.exec(id,
                List.of("sh", "-c", "echo exec-out; echo exec-err 1>&2; exit 3"));

            assertThat(result.stdout()).contains("exec-out");          // stdout frame
            assertThat(result.stdout()).doesNotContain("exec-err");    // kept separate
            assertThat(result.stderr()).contains("exec-err");          // stderr frame
            assertThat(result.exitCode()).isEqualTo(3);                // exit code captured
        } finally {
            docker.stopContainer(id, 1);
            docker.removeContainer(id, true);
        }
    }

    @Test
    void toJsonEncodesNestedSpecWithEscaping() {
        String json = DockerClient.toJson(Map.of(
            "Image", "alpine",
            "Tty", true,
            "Cmd", List.of("sh", "-c", "echo \"hi\"")
        ));
        assertThat(json).contains("\"Image\":\"alpine\"");
        assertThat(json).contains("\"Tty\":true");
        assertThat(json).contains("\"Cmd\":[\"sh\",\"-c\",");
        assertThat(json).contains("echo \\\"hi\\\"");   // embedded quotes escaped
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
