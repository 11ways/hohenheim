package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.stack.StackDeployer;
import be.elevenways.hohenheim.server.stack.StackSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * StackDeployer against a real Docker daemon: network + volumes + dependency
 * ordering + config file upload + ownership protection + replace + destroy.
 * Skipped when the daemon socket or alpine image is absent.
 */
class StackDeployerTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";

    private final DockerClient docker = new DockerClient();
    private final List<StackSpec> deployedSpecs = new ArrayList<>();
    private final List<String> strayContainers = new ArrayList<>();

    private static String uniqueStackName() {
        return "hhtest-" + Long.toHexString(System.nanoTime());
    }

    private static StackSpec.ServiceSpec sleeper(String name, List<StackSpec.DependsSpec> depends,
                                                 List<StackSpec.MountSpec> mounts,
                                                 List<StackSpec.FileSpec> files,
                                                 String healthCmd) {
        return new StackSpec.ServiceSpec(name, TEST_IMAGE, List.of("sleep", "600"), Map.of("ROLE", name),
            mounts, List.of(), depends, files,
            healthCmd, 1, 3, 3, 0, "no", null, null);
    }

    private StackSpec spec(String stackName, boolean adopt, StackSpec.ServiceSpec... services) {
        return new StackSpec(0, stackName, "local", null, adopt, null, null, null,
            StackSpec.topologicallySorted(List.of(services)));
    }

    private StackDeployer deployer(StringBuilder log) {
        return new StackDeployer(docker, log == null ? null : line -> log.append(line).append('\n'));
    }

    private void requireDocker() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assumeTrue(imagePresent(), TEST_IMAGE + " not present locally");
    }

    private boolean imagePresent() throws IOException {
        for (Object image : docker.listImages()) {
            Object tags = ((Map<?, ?>) image).get("RepoTags");
            if (tags instanceof List<?> list && list.contains(TEST_IMAGE)) {
                return true;
            }
        }
        return false;
    }

    @AfterEach
    void cleanup() {
        for (StackSpec spec : deployedSpecs) {
            try {
                new StackDeployer(docker, null).destroy(spec, true);
            } catch (Exception ignored) {
                // best effort
            }
        }
        for (String container : strayContainers) {
            try {
                docker.removeContainer(container, true);
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deploysNetworkVolumesFilesAndDependencyOrder() throws IOException {
        requireDocker();
        String stackName = uniqueStackName();

        StackSpec.ServiceSpec base = sleeper("base", List.of(),
            List.of(new StackSpec.MountSpec("volume", "data", "/data", null)),
            List.of(new StackSpec.FileSpec("/etc/hhtest/config.yaml", "answer: 42\n", "0600")),
            // The file marker makes health provable: healthy only once the config exists.
            "test -f /etc/hhtest/config.yaml");
        StackSpec.ServiceSpec follower = sleeper("follower",
            List.of(new StackSpec.DependsSpec("base", "healthy")),
            List.of(), List.of(), null);

        StackSpec spec = spec(stackName, false, base, follower);
        deployedSpecs.add(spec);

        StringBuilder log = new StringBuilder();
        deployer(log).deploy(spec);

        // Network exists, owned, with both containers attached.
        Map<String, Object> network = docker.inspectNetwork(StackDeployer.networkName(spec));
        Map<String, Object> networkLabels = (Map<String, Object>) network.get("Labels");
        assertThat(networkLabels).containsEntry(StackDeployer.LABEL_STACK, stackName);
        assertThat((Map<String, Object>) network.get("Containers")).hasSize(2);

        // Volume exists and is owned.
        Map<String, Object> volume = docker.inspectVolume("hohenheim-stack-" + stackName + "-data");
        assertThat((Map<String, Object>) volume.get("Labels"))
            .containsEntry(StackDeployer.LABEL_STACK, stackName);

        // The config file arrived with its mode before start.
        String baseContainer = StackDeployer.containerName(spec, "base");
        DockerClient.ExecResult content = docker.exec(baseContainer,
            List.of("cat", "/etc/hhtest/config.yaml"));
        assertThat(content.exitCode()).isEqualTo(0);
        assertThat(content.stdout()).contains("answer: 42");
        DockerClient.ExecResult mode = docker.exec(baseContainer,
            List.of("stat", "-c", "%a", "/etc/hhtest/config.yaml"));
        assertThat(mode.stdout().trim()).isEqualTo("600");

        // The follower only started after base reported healthy.
        assertThat(log.toString()).contains("Waiting for 'base' to be healthy");
        Map<String, String> states = deployer(null).status(spec);
        assertThat(states.get("base")).isEqualTo("healthy");
        assertThat(states.get("follower")).isEqualTo("running");

        // DNS alias: the follower reaches the base service by name on the stack network.
        DockerClient.ExecResult resolve = docker.exec(
            StackDeployer.containerName(spec, "follower"),
            List.of("ping", "-c", "1", "-W", "2", "base"));
        assertThat(resolve.exitCode())
            .withFailMessage("service alias did not resolve: %s%s", resolve.stdout(), resolve.stderr())
            .isEqualTo(0);
    }

    /**
     * A spec carrying its record id stamps the reconciler's owner labels onto
     * container, volume AND network, WITHOUT changing what the deployer is willing
     * to touch: ownership matching stays keyed on the stack-name label alone, so a
     * same-named foreign resource is still refused.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ownerLabelsRideAlongsideStackLabelsWithoutWideningOwnership() throws IOException {
        requireDocker();
        String stackName = uniqueStackName();
        int stackRecordId = 31337;

        StackSpec.ServiceSpec app = sleeper("app", List.of(),
            List.of(new StackSpec.MountSpec("volume", "data", "/data", null)),
            List.of(), null);
        StackSpec spec = new StackSpec(stackRecordId, stackName, "local", null, false,
            null, null, null, StackSpec.topologicallySorted(List.of(app)));
        deployedSpecs.add(spec);

        // 1. Deploy: every created resource carries stack labels AND the owner pair.
        deployer(null).deploy(spec);

        Map<String, Object> container = docker.inspectContainer(StackDeployer.containerName(spec, "app"));
        Map<String, Object> containerLabels =
            (Map<String, Object>) ((Map<String, Object>) container.get("Config")).get("Labels");
        assertThat(containerLabels)
            .as("container keeps the stack-name labels")
            .containsEntry(StackDeployer.LABEL_STACK, stackName)
            .containsEntry(StackDeployer.LABEL_SERVICE, "app");
        OwnerLabels.Owner containerOwner = OwnerLabels.parse(containerLabels);
        assertThat(containerOwner).as("container carries the owner pair").isNotNull();
        assertThat(containerOwner.model()).isEqualTo(StackModel.MODEL_ID);
        assertThat(containerOwner.id()).isEqualTo(String.valueOf(stackRecordId));

        Map<String, Object> volume = docker.inspectVolume("hohenheim-stack-" + stackName + "-data");
        OwnerLabels.Owner volumeOwner = OwnerLabels.parse((Map<?, ?>) volume.get("Labels"));
        assertThat(volumeOwner).as("volume carries the owner pair from birth").isNotNull();
        assertThat(volumeOwner.id()).isEqualTo(String.valueOf(stackRecordId));

        Map<String, Object> network = docker.inspectNetwork(StackDeployer.networkName(spec));
        OwnerLabels.Owner networkOwner = OwnerLabels.parse((Map<?, ?>) network.get("Labels"));
        assertThat(networkOwner).as("network carries the owner pair").isNotNull();
        assertThat(networkOwner.model()).isEqualTo(StackModel.MODEL_ID);

        // 2. Destroy with volumes removes exactly this stack's resources, keyed on
        //    the stack-name label as before.
        deployer(null).destroy(spec, true);
        String containerName = StackDeployer.containerName(spec, "app");
        assertThatThrownBy(() -> docker.inspectContainer(containerName))
            .as("container removed by destroy").isInstanceOf(IOException.class);
        assertThatThrownBy(() -> docker.inspectVolume("hohenheim-stack-" + stackName + "-data"))
            .as("owned volume removed by destroy").isInstanceOf(IOException.class);

        // 3. Ownership matching did NOT widen: a same-named container carrying ONLY
        //    the owner pair (no stack-name label) is still refused, because isOwned
        //    keys on the stack-name label alone. (The refused deploy re-creates the
        //    stack network/volume first; @AfterEach's destroy sweeps those.)
        strayContainers.add(containerName);
        Map<String, Object> impostorSpec = new java.util.LinkedHashMap<>();
        impostorSpec.put("Image", TEST_IMAGE);
        impostorSpec.put("Cmd", List.of("sleep", "600"));
        impostorSpec.put("Labels", OwnerLabels.of(StackModel.MODEL_ID, stackRecordId));
        docker.createContainer(containerName, impostorSpec);
        assertThatThrownBy(() -> deployer(null).deploy(spec))
            .as("owner labels alone never make a resource replaceable")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not owned by this stack");
    }

    @Test
    void replaceKeepsVolumeDataAcrossRedeploys() throws IOException {
        requireDocker();
        String stackName = uniqueStackName();

        StackSpec.ServiceSpec writer = sleeper("writer", List.of(),
            List.of(new StackSpec.MountSpec("volume", "keep", "/keep", null)),
            List.of(), null);
        StackSpec spec = spec(stackName, false, writer);
        deployedSpecs.add(spec);

        deployer(null).deploy(spec);
        String container = StackDeployer.containerName(spec, "writer");
        assertThat(docker.exec(container,
            List.of("sh", "-c", "echo survived > /keep/marker")).exitCode()).isEqualTo(0);

        // Re-deploy: the container is replaced, the named volume is not.
        deployer(null).deploy(spec);
        DockerClient.ExecResult marker = docker.exec(container, List.of("cat", "/keep/marker"));
        assertThat(marker.exitCode()).isEqualTo(0);
        assertThat(marker.stdout()).contains("survived");
    }

    @Test
    void refusesUnownedContainerUnlessAdopting() throws IOException {
        requireDocker();
        String stackName = uniqueStackName();

        StackSpec.ServiceSpec app = sleeper("app", List.of(), List.of(), List.of(), null);
        StackSpec strict = spec(stackName, false, app);

        // Somebody else's container squats the name (no ownership labels).
        String squatted = StackDeployer.containerName(strict, "app");
        strayContainers.add(squatted);
        docker.createContainer(squatted, Map.of("Image", TEST_IMAGE, "Cmd", List.of("sleep", "600")));

        assertThatThrownBy(() -> deployer(null).deploy(strict))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not owned by this stack");

        // With adoption the deploy replaces it and takes ownership.
        StackSpec adopting = spec(stackName, true, app);
        deployedSpecs.add(adopting);
        deployer(null).deploy(adopting);
        Map<String, String> states = deployer(null).status(adopting);
        assertThat(states.get("app")).isEqualTo("running");
    }

    @Test
    void destroyRemovesContainersNetworkAndOwnedVolumes() throws IOException {
        requireDocker();
        String stackName = uniqueStackName();

        StackSpec.ServiceSpec app = sleeper("app", List.of(),
            List.of(new StackSpec.MountSpec("volume", "gone", "/gone", null)),
            List.of(), null);
        StackSpec spec = spec(stackName, false, app);

        deployer(null).deploy(spec);
        deployer(null).destroy(spec, true);

        assertThatThrownBy(() -> docker.inspectContainer(StackDeployer.containerName(spec, "app")))
            .isInstanceOf(IOException.class);
        assertThat(docker.findNetworkByName(StackDeployer.networkName(spec))).isNull();
        assertThatThrownBy(() -> docker.inspectVolume("hohenheim-stack-" + stackName + "-gone"))
            .isInstanceOf(IOException.class);
    }

    /**
     * Declarative convergence: a service dropped from the spec (deleted, disabled or
     * renamed) leaves a container that status/stop/destroy would never see again,
     * running forever under its restart policy. The redeploy must prune it.
     */
    @Test
    void redeployPrunesContainersOfServicesNoLongerInTheSpec() throws IOException {
        requireDocker();
        String stackName = uniqueStackName();

        StackSpec.ServiceSpec kept = sleeper("kept", List.of(), List.of(), List.of(), null);
        StackSpec.ServiceSpec dropped = sleeper("dropped", List.of(), List.of(), List.of(), null);

        StackSpec both = spec(stackName, false, kept, dropped);
        deployedSpecs.add(both);
        deployer(null).deploy(both);
        assertThat(docker.inspectContainer(StackDeployer.containerName(both, "dropped"))).isNotNull();

        StackSpec reduced = spec(stackName, false, kept);
        deployedSpecs.add(reduced);
        StringBuilder log = new StringBuilder();
        deployer(log).deploy(reduced);

        assertThat(docker.inspectContainer(StackDeployer.containerName(reduced, "kept"))).isNotNull();
        assertThatThrownBy(() -> docker.inspectContainer(StackDeployer.containerName(both, "dropped")))
            .as("the dropped service's container must not survive the redeploy")
            .isInstanceOf(IOException.class);
        assertThat(log.toString()).contains("Pruning orphaned container");
    }

    /** Pruning is label-scoped: an unrelated container with no stack label is untouched. */
    @Test
    void pruningNeverTouchesContainersOutsideTheStack() throws IOException {
        requireDocker();
        String stackName = uniqueStackName();
        String bystander = "hhtest-bystander-" + Long.toHexString(System.nanoTime());

        docker.createContainer(bystander, Map.of(
            "Image", TEST_IMAGE,
            "Cmd", List.of("sleep", "600")));
        strayContainers.add(bystander);
        docker.startContainer(bystander);

        StackSpec spec = spec(stackName, false,
            sleeper("app", List.of(), List.of(), List.of(), null));
        deployedSpecs.add(spec);
        deployer(null).deploy(spec);

        assertThat(docker.inspectContainer(bystander))
            .as("a container without this stack's ownership label must survive a deploy")
            .isNotNull();
    }

    @Test
    void stopHaltsContainersWithoutRemovingThem() throws IOException {
        requireDocker();
        String stackName = uniqueStackName();

        StackSpec.ServiceSpec app = sleeper("app", List.of(), List.of(), List.of(), null);
        StackSpec spec = spec(stackName, false, app);
        deployedSpecs.add(spec);

        deployer(null).deploy(spec);
        deployer(null).stop(spec);

        Map<String, String> states = deployer(null).status(spec);
        assertThat(states.get("app")).isEqualTo("stopped");
    }
}
