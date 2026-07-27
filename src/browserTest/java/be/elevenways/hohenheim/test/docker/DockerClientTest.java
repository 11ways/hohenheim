package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ProcessDockerTransport;
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
    void infoReportsHostResources() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        Map<String, Object> info = new DockerClient().info();
        assertThat(info).containsKey("NCPU");
        assertThat(info).containsKey("MemTotal");
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
    void worksOverProcessTransportViaDialStdio() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        // `docker system dial-stdio` bridges stdio to the local daemon -- the same mechanism the
        // SSH transport uses against a remote host, exercised here without needing SSH.
        DockerClient docker = new DockerClient(
            new ProcessDockerTransport(List.of("docker", "system", "dial-stdio")));

        assertThat(docker.ping()).isTrue();
        assertThat(docker.version()).containsKey("ApiVersion");
        assertThat(docker.listContainers(true)).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void networkLifecycleWithLabelsSubnetAndAliases() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        String networkName = "hohenheim-nettest-" + System.nanoTime();
        String containerId = null;
        String networkId = null;
        try {
            networkId = docker.createNetwork(networkName,
                Map.of("be.elevenways.hohenheim.test", "true"), "172.29.111.0/24", "172.29.111.1");
            assertThat(networkId).isNotBlank();

            Map<String, Object> inspected = docker.inspectNetwork(networkName);
            assertThat(inspected.get("Name")).isEqualTo(networkName);
            Map<String, Object> labels = (Map<String, Object>) inspected.get("Labels");
            assertThat(labels).containsEntry("be.elevenways.hohenheim.test", "true");

            Map<String, Object> found = docker.findNetworkByName(networkName);
            assertThat(found).isNotNull();
            assertThat(found.get("Id")).isNotNull();
            assertThat(docker.findNetworkByName(networkName + "-missing")).isNull();

            containerId = docker.createContainer("hohenheim-nettest-c-" + System.nanoTime(), Map.of(
                "Image", TEST_IMAGE,
                "Cmd", List.of("sleep", "30")
            ));
            docker.startContainer(containerId);
            docker.connectContainerToNetwork(networkName, containerId, List.of("svc-alias"));

            Map<String, Object> withContainer = docker.inspectNetwork(networkName);
            Map<String, Object> containers = (Map<String, Object>) withContainer.get("Containers");
            assertThat(containers).isNotEmpty();

            docker.disconnectContainerFromNetwork(networkName, containerId, true);
            Map<String, Object> afterDisconnect = docker.inspectNetwork(networkName);
            Map<String, Object> remaining = (Map<String, Object>) afterDisconnect.get("Containers");
            assertThat(remaining == null || remaining.isEmpty()).isTrue();
        } finally {
            if (containerId != null) {
                try {
                    docker.removeContainer(containerId, true);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
            if (networkId != null) {
                try {
                    docker.removeNetwork(networkName);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }

        try {
            docker.inspectNetwork(networkName);
            throw new AssertionError("expected inspect of removed network to fail");
        } catch (IOException expected) {
            // 404 -> IOException, as intended
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void volumeLifecycleWithLabels() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();

        String volumeName = "hohenheim-voltest-" + System.nanoTime();
        try {
            Map<String, Object> created = docker.createVolume(volumeName,
                Map.of("be.elevenways.hohenheim.test", "true"));
            assertThat(created.get("Name")).isEqualTo(volumeName);

            Map<String, Object> inspected = docker.inspectVolume(volumeName);
            assertThat(inspected.get("Name")).isEqualTo(volumeName);
            assertThat((String) inspected.get("Mountpoint")).isNotBlank();
            Map<String, Object> labels = (Map<String, Object>) inspected.get("Labels");
            assertThat(labels).containsEntry("be.elevenways.hohenheim.test", "true");

            boolean listed = docker.listVolumes().stream()
                .anyMatch(entry -> entry instanceof Map<?, ?> volume
                    && volumeName.equals(volume.get("Name")));
            assertThat(listed).isTrue();

            // createVolume is idempotent per name: a second create returns the same volume.
            Map<String, Object> again = docker.createVolume(volumeName, null);
            assertThat(again.get("Name")).isEqualTo(volumeName);
        } finally {
            try {
                docker.removeVolume(volumeName, true);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }

        try {
            docker.inspectVolume(volumeName);
            throw new AssertionError("expected inspect of removed volume to fail");
        } catch (IOException expected) {
            // 404 -> IOException, as intended
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fixedHostPortPublicationIncludingUdp() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        // High ports chosen to avoid collisions; loopback-bound so nothing is exposed.
        int tcpPort = 42000 + (int) (System.nanoTime() % 1000);
        int udpPort = tcpPort + 1000;

        String id = docker.createContainer("hohenheim-porttest-" + System.nanoTime(), Map.of(
            "Image", TEST_IMAGE,
            "Cmd", List.of("sleep", "30"),
            "ExposedPorts", Map.of("7000/tcp", Map.of(), "7001/udp", Map.of()),
            "HostConfig", Map.of("PortBindings", Map.of(
                "7000/tcp", List.of(Map.of("HostIp", "127.0.0.1", "HostPort", String.valueOf(tcpPort))),
                "7001/udp", List.of(Map.of("HostIp", "127.0.0.1", "HostPort", String.valueOf(udpPort)))
            ))
        ));
        try {
            docker.startContainer(id);

            Map<String, Object> settings =
                (Map<String, Object>) docker.inspectContainer(id).get("NetworkSettings");
            Map<String, Object> ports = (Map<String, Object>) settings.get("Ports");

            List<Object> tcpBindings = (List<Object>) ports.get("7000/tcp");
            assertThat(tcpBindings).isNotEmpty();
            assertThat(((Map<String, Object>) tcpBindings.get(0)).get("HostPort"))
                .isEqualTo(String.valueOf(tcpPort));

            List<Object> udpBindings = (List<Object>) ports.get("7001/udp");
            assertThat(udpBindings).isNotEmpty();
            assertThat(((Map<String, Object>) udpBindings.get(0)).get("HostPort"))
                .isEqualTo(String.valueOf(udpPort));
        } finally {
            docker.removeContainer(id, true);
        }
    }

    @Test
    void restartContainerKeepsItRunning() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        String id = docker.createContainer("hohenheim-restarttest-" + System.nanoTime(), Map.of(
            "Image", TEST_IMAGE,
            "Cmd", List.of("sleep", "30")
        ));
        try {
            docker.startContainer(id);
            String firstStart = startedAt(docker, id);

            docker.restartContainer(id, 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) docker.inspectContainer(id).get("State");
            assertThat(state.get("Running")).isEqualTo(Boolean.TRUE);
            assertThat(startedAt(docker, id)).isNotEqualTo(firstStart);
        } finally {
            docker.removeContainer(id, true);
        }
    }

    @SuppressWarnings("unchecked")
    private static String startedAt(DockerClient docker, String id) throws IOException {
        Map<String, Object> state = (Map<String, Object>) docker.inspectContainer(id).get("State");
        return String.valueOf(state.get("StartedAt"));
    }

    @Test
    void ensureImageRecognizesDigestPinnedReferences() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, TEST_IMAGE), TEST_IMAGE + " not present locally");

        // Find the locally-present alpine's digest and ensure it: must be a no-op
        // (no network), proving digest refs are matched against RepoDigests.
        String digestRef = null;
        for (Object image : docker.listImages()) {
            Object tags = ((Map<?, ?>) image).get("RepoTags");
            if (tags instanceof List<?> list && list.contains(TEST_IMAGE)) {
                Object digests = ((Map<?, ?>) image).get("RepoDigests");
                if (digests instanceof List<?> refs && !refs.isEmpty()) {
                    digestRef = String.valueOf(refs.get(0));
                }
                break;
            }
        }
        assumeTrue(digestRef != null, "local alpine has no RepoDigest");
        docker.ensureImage(digestRef, null);   // throws if it tried (and failed) to re-pull
    }

    @Test
    void registryAuthEncodesBase64Json() {
        DockerClient.RegistryAuth auth =
            new DockerClient.RegistryAuth("robot", "s3cret\"quote", "ghcr.io");
        String decoded = new String(
            java.util.Base64.getUrlDecoder().decode(auth.encode()),
            java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded).contains("\"username\":\"robot\"");
        assertThat(decoded).contains("s3cret");
        assertThat(decoded).contains("\"serveraddress\":\"ghcr.io\"");

        DockerClient.RegistryAuth hub = new DockerClient.RegistryAuth("user", "pw", null);
        String hubDecoded = new String(
            java.util.Base64.getUrlDecoder().decode(hub.encode()),
            java.nio.charset.StandardCharsets.UTF_8);
        assertThat(hubDecoded).doesNotContain("serveraddress");
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
