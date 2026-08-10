package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackServiceKind;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Phase 7 stack lowering, proven AT THE DAEMON: every stack service IS an owned
 * instance, the shared per-stack network is a policied LINK network carrying the compose
 * DNS aliases, dependency ordering still gates starts, and the instance-tier mechanisms
 * the tier never had before (port ledger claim-before-create, charge==cap capacity,
 * verified destroy) genuinely apply to it.
 *
 * Real records in a temp SQLite, the real local Docker daemon, and the PRODUCTION network
 * policy against real nftables in a {@link PrivateNetns} -- a machine that cannot build
 * one SKIPS visibly instead of passing policy-less.
 */
class StackInstancesTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";

    private static SqliteDatasource datasource;
    private static StackRuntime runtime;
    private static DockerClient docker;
    private static PrivateNetns netns;

    private final List<Integer> stacks = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-stackinst-enc").resolve("keys.dry")));
        File db = File.createTempFile("hohenheim-stackinst-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        netns = PrivateNetns.installEnforcing();
        docker = new DockerClient();
        runtime = new StackRuntime(docker, datasource);
    }

    @AfterAll
    static void tearDown() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    @AfterEach
    void cleanup() {
        for (Integer stackId : stacks) {
            try {
                runtime.destroy(stackId, true);
            } catch (Exception ignored) {
                // best effort
            }
        }
        stacks.clear();
    }

    private void requireDocker() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: a stack refuses to deploy where its policy cannot be enforced");
        LiveLane.requireImage(docker, TEST_IMAGE);
    }

    // -- record helpers --------------------------------------------------------

    private int newStack(String name) {
        int[] id = new int[1];
        Db.run(datasource, () -> {
            StackModel model = Models.get(StackModel.class);
            Row stack = model.createEmptyRow();
            stack.set(StackModel.NAME, name);
            stack.set(StackModel.ENABLED, true);
            stack.set(StackModel.SERVER_ID, ServerModel.localServerId());
            model.save(stack);
            id[0] = stack.get(StackModel.ID);
        });
        stacks.add(id[0]);
        return id[0];
    }

    /** One sleeping service; every optional shape is set by the caller afterwards. */
    private int newService(int stackId, String name) {
        int[] id = new int[1];
        Db.run(datasource, () -> {
            StackServiceModel model = Models.get(StackServiceModel.class);
            Row service = model.createEmptyRow();
            service.set(StackServiceModel.STACK_ID, stackId);
            service.set(StackServiceModel.NAME, name);
            service.set(StackServiceModel.ENABLED, true);
            service.set(StackServiceModel.IMAGE, TEST_IMAGE);
            service.set(StackServiceModel.COMMAND, List.of("sleep", "600"));
            service.set(StackServiceModel.RESTART_POLICY, "no");
            model.save(service);
            id[0] = service.get(StackServiceModel.ID);
        });
        return id[0];
    }

    private void editService(int serviceId, java.util.function.Consumer<Row> edit) {
        Db.run(datasource, () -> {
            StackServiceModel model = Models.get(StackServiceModel.class);
            Row service = model.findById(serviceId);
            edit.accept(service);
            model.save(service);
        });
    }

    private static Row mount(String type, String name, String path) {
        Row row = new Row();
        row.set(StackServiceModel.MOUNT_TYPE, type);
        row.set(StackServiceModel.MOUNT_NAME, name);
        row.set(StackServiceModel.MOUNT_PATH, path);
        return row;
    }

    private static Row port(int container, int host, String protocol, String hostIp) {
        Row row = new Row();
        row.set(StackServiceModel.PORT_CONTAINER, container);
        row.set(StackServiceModel.PORT_HOST, host);
        row.set(StackServiceModel.PORT_PROTOCOL, protocol);
        row.set(StackServiceModel.PORT_HOST_IP, hostIp);
        return row;
    }

    /** THE daemon-side container name of a service, resolved through its owned instance. */
    private String containerOf(int serviceId) {
        Integer[] instanceId = new Integer[1];
        Db.run(datasource, () -> {
            Row instance = StackInstances.owned(serviceId);
            instanceId[0] = instance == null ? null : instance.get(InstanceModel.ID);
        });
        assertThat(instanceId[0]).as("service %s owns an instance", serviceId).isNotNull();
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId[0]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> labelsOf(Map<String, Object> inspected) {
        Object config = inspected.get("Config");
        Object labels = config instanceof Map<?, ?> map ? map.get("Labels") : null;
        return labels instanceof Map<?, ?> ? (Map<String, Object>) labels : Map.of();
    }

    // -- journeys ---------------------------------------------------------------

    /**
     * The lowering itself, end to end: two services become two owned instances, each on
     * its OWN private network plus the stack's shared link network, reaching each other
     * by compose service name, with ordering gated on a real healthcheck the RUNTIME
     * evaluates -- every fact read back off the daemon.
     */
    @Test
    @SuppressWarnings("unchecked")
    void servicesBecomeOwnedInstancesOnASharedLinkNetworkWithComposeDns() throws IOException {
        requireDocker();
        String stackName = "hhlow-" + Long.toHexString(System.nanoTime());
        int stackId = newStack(stackName);
        int baseId = newService(stackId, "base");
        int followerId = newService(stackId, "follower");
        editService(baseId, service -> {
            service.setRecords(StackServiceModel.MOUNTS,
                List.of(mount(StackServiceModel.MOUNT_VOLUME, "data", "/data")));
            // The file marker makes health PROVABLE: healthy only once the config exists.
            service.set(StackServiceModel.HEALTH_CMD, "test -f /etc/hhtest/config.yaml");
            service.set(StackServiceModel.HEALTH_INTERVAL_SECONDS, 1);
            service.set(StackServiceModel.HEALTH_TIMEOUT_SECONDS, 3);
            service.set(StackServiceModel.HEALTH_RETRIES, 3);
        });
        Db.run(datasource, () -> {
            StackFileModel files = Models.get(StackFileModel.class);
            Row file = files.createEmptyRow();
            file.set(StackFileModel.STACK_SERVICE_ID, baseId);
            file.set(StackFileModel.CONTAINER_PATH, "/etc/hhtest/config.yaml");
            file.set(StackFileModel.CONTENT, "answer: 42\n");
            file.set(StackFileModel.MODE, "0600");
            files.save(file);
        });
        Row followerDepends = new Row();
        followerDepends.set(StackServiceModel.DEPENDS_SERVICE, "base");
        followerDepends.set(StackServiceModel.DEPENDS_CONDITION, StackServiceModel.CONDITION_HEALTHY);
        editService(followerId, service ->
            service.setRecords(StackServiceModel.DEPENDS_ON, List.of(followerDepends)));

        // 1. Deploy from records.
        runtime.deploy(stackId, "test");

        // 2. Each service OWNS an instance of the stack_service kind, attributed to its
        //    own record -- the GeneratedRows discipline, read from the rows.
        Db.run(datasource, () -> {
            for (int serviceId : List.of(baseId, followerId)) {
                Row instance = StackInstances.owned(serviceId);
                assertThat(instance)
                    .as("step 2: service %s owns an instance", serviceId).isNotNull();
                assertThat((String) instance.get(InstanceModel.KIND))
                    .as("step 2: of the stack_service kind")
                    .isEqualTo(StackServiceKind.ID.toString());
                assertThat((String) instance.get(InstanceModel.GENERATED_FOR_MODEL))
                    .as("step 2: attributed to the SERVICE record, not the stack")
                    .isEqualTo(StackServiceModel.MODEL_ID.toString());
                assertThat((String) instance.get(InstanceModel.GENERATED_BY))
                    .as("step 2: written by the stack tier's system scope")
                    .isEqualTo(StackInstances.SOURCE);
            }
        });

        String baseContainer = containerOf(baseId);
        String followerContainer = containerOf(followerId);

        // 3. THE DAEMON's own attribution is the INSTANCE, not the stack name: the
        //    lowered container is an ordinary instance container in every way.
        Map<String, Object> baseInspect = docker.inspectContainer(baseContainer);
        OwnerLabels.Owner owner = OwnerLabels.parse(labelsOf(baseInspect));
        assertThat(owner).as("step 3: the daemon attributes the container").isNotNull();
        assertThat(owner.model())
            .as("step 3: to the INSTANCE model").isEqualTo(InstanceModel.MODEL_ID);
        assertThat(labelsOf(baseInspect))
            .as("step 3: and carries no pre-lowering stack-name label at all")
            .doesNotContainKey(StackInstances.LEGACY_LABEL_STACK);

        // 4. TWO networks per service: its own private per-workload network AND the
        //    stack's shared link network. The pre-lowering tier had only the shared one.
        String linkNetwork = WorkloadNetworks.networkName(StackInstances.networkHandle(stackName));
        String ownNetwork = WorkloadNetworks.networkName(baseContainer);
        Map<String, Object> baseNetworks = (Map<String, Object>)
            ((Map<String, Object>) baseInspect.get("NetworkSettings")).get("Networks");
        assertThat(baseNetworks.keySet())
            .as("step 4: the service is on its own network and the shared stack network")
            .containsExactlyInAnyOrder(ownNetwork, linkNetwork);

        // 5. The shared network is owned by the STACK record and policied like any other.
        Map<String, Object> link = docker.inspectNetwork(linkNetwork);
        OwnerLabels.Owner linkOwner = OwnerLabels.parse((Map<?, ?>) link.get("Labels"));
        assertThat(linkOwner).as("step 5: the shared network is attributed").isNotNull();
        assertThat(linkOwner.model())
            .as("step 5: to the STACK record, which is what outlives one service")
            .isEqualTo(StackModel.MODEL_ID);
        assertThat(linkOwner.id()).isEqualTo(String.valueOf(stackId));
        String forwardRules = netns.inHost("nft", "list", "chain", "inet",
            WorkloadNetworkPolicy.table(),
            WorkloadNetworkPolicy.forwardChain(WorkloadNetworkPolicy.chainKey(linkNetwork)))
            .stdout();
        assertThat(forwardRules)
            .as("step 5: the shared network's metadata deny is IN THE KERNEL")
            .contains("ip daddr 169.254.0.0/16 drop");

        // 6. Compose DNS: the follower reaches base by SERVICE NAME over the shared
        //    network. This is the alias, asserted at the daemon by resolving it.
        DockerClient.ExecResult resolve = docker.exec(followerContainer,
            List.of("ping", "-c", "1", "-W", "2", "base"));
        assertThat(resolve.exitCode())
            .withFailMessage("step 6: the compose service alias did not resolve: %s%s",
                resolve.stdout(), resolve.stderr())
            .isEqualTo(0);
        // The engine's own reply text, not only the exit code: ping variants have exited
        // 0 while printing a refusal, and "resolved" must mean a reply actually came back.
        assertThat(resolve.stdout())
            .withFailMessage("step 6: ping exited 0 but printed no clean reply for the"
                + " alias: stdout=%s stderr=%s", resolve.stdout(), resolve.stderr())
            .contains("bytes from")
            .contains(" 0% packet loss");

        // 7. Config files rode the INSTANCE file-staging lane, mode included.
        DockerClient.ExecResult content = docker.exec(baseContainer,
            List.of("cat", "/etc/hhtest/config.yaml"));
        assertThat(content.exitCode()).as("step 7: the config file exists").isEqualTo(0);
        assertThat(content.stdout()).contains("answer: 42");
        assertThat(docker.exec(baseContainer,
            List.of("stat", "-c", "%a", "/etc/hhtest/config.yaml")).stdout().trim())
            .as("step 7: with its declared mode").isEqualTo("600");

        // 8. Ordering: the declared healthcheck is evaluated BY THE RUNTIME and the
        //    follower waited for it. "healthy" here is the daemon's own verdict.
        Map<String, String> states = runtime.serviceStates(stackId);
        assertThat(states.get("base"))
            .as("step 8: the daemon reports the declared healthcheck as healthy")
            .isEqualTo("healthy");
        assertThat(states.get("follower")).as("step 8: the follower runs").isEqualTo("running");
    }

    /**
     * The lowering's counterfactual: mechanisms the stack tier DID NOT HAVE now apply,
     * asserted at the daemon and in the ledger rather than through our own bookkeeping.
     * Before this wave a stack service's host port was claimed at SAVE time under the
     * SERVICE, with no daemon evidence, and its memory was booked NOWHERE at all.
     */
    @Test
    void loweredServicesGainTheLedgerClaimAndTheCapacityCap() throws IOException {
        requireDocker();
        String stackName = "hhledg-" + Long.toHexString(System.nanoTime());
        int stackId = newStack(stackName);
        int serviceId = newService(stackId, "web");
        int hostPort = 34100 + (int) (System.nanoTime() % 400);
        editService(serviceId, service ->
            service.setRecords(StackServiceModel.PORTS,
                List.of(port(80, hostPort, "tcp", "127.0.0.1"))));

        // 1. Before the deploy, NOTHING holds the port -- the save no longer claims it.
        Db.run(datasource, () -> assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(
            ServerModel.localServerId(), "127.0.0.1", hostPort, "tcp")))
            .as("step 1: a save claims no port; the deploy does")
            .isNull());

        runtime.deploy(stackId, "ledger");
        String container = containerOf(serviceId);

        // 2. The claim exists and is owned by the INSTANCE, pre-allocated (fixed host
        //    port), which is what makes it verifiable against the daemon afterwards.
        Db.run(datasource, () -> {
            Row instance = StackInstances.owned(serviceId);
            List<Row> claims = PortLedger.claimsOf(InstanceModel.MODEL_ID,
                instance.get(InstanceModel.ID));
            assertThat(claims)
                .as("step 2: the deploy claimed the declared host port under the INSTANCE")
                .hasSize(1);
            assertThat((Integer) claims.get(0).get(PortAllocationModel.PORT)).isEqualTo(hostPort);
            assertThat(PortLedger.claimsOf(StackServiceModel.MODEL_ID, serviceId))
                .as("step 2: and the service record holds none -- one owner per port")
                .isEmpty();
        });

        // 3. The DAEMON bound exactly that number on loopback (the after-start
        //    verification's own subject, read back independently here).
        assertThat(docker.publishedPort(container, 80))
            .as("step 3: the daemon published the claimed number")
            .isEqualTo(hostPort);

        // 4. Charge == cap: the kind's declared footprint is the cgroup ceiling the
        //    daemon actually applied. The pre-lowering tier applied no default at all.
        Object hostConfig = docker.inspectContainer(container).get("HostConfig");
        Object memory = hostConfig instanceof Map<?, ?> map ? map.get("Memory") : null;
        assertThat(memory)
            .as("step 4: the booked footprint is the enforced cgroup cap")
            .isInstanceOf(Number.class);
        assertThat(((Number) memory).longValue())
            .isEqualTo(512L * 1024 * 1024);

        // 5. A rival service declaring the SAME host port is refused BY NAME at deploy,
        //    and nothing of it reaches the daemon.
        String rivalStackName = "hhledg2-" + Long.toHexString(System.nanoTime());
        int rivalStackId = newStack(rivalStackName);
        int rivalId = newService(rivalStackId, "rival");
        editService(rivalId, service ->
            service.setRecords(StackServiceModel.PORTS,
                List.of(port(80, hostPort, "tcp", "127.0.0.1"))));
        assertThatThrownBy(() -> runtime.deploy(rivalStackId, "conflict"))
            .as("step 5: the ledger refuses the contested port")
            .isInstanceOf(IOException.class)
            .hasMessageContaining(String.valueOf(hostPort));
        String rivalContainer = containerOf(rivalId);
        assertThatThrownBy(() -> docker.inspectContainer(rivalContainer))
            .as("step 5: and the rival's container was never created")
            .isInstanceOf(IOException.class);

        // 6. Verified destroy releases the claim fully.
        runtime.destroy(stackId, true);
        Db.run(datasource, () -> assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(
            ServerModel.localServerId(), "127.0.0.1", hostPort, "tcp")))
            .as("step 6: destroy released the claim, so the port is reusable")
            .isNull());
    }

    /**
     * Convergence: a service DISABLED since the last deploy leaves a workload that
     * status, stop and destroy would never see again -- the redeploy must destroy it,
     * and the surviving service's named volume must keep its data across the replace.
     */
    @Test
    void redeployPrunesDisabledServicesAndKeepsVolumeData() throws IOException {
        requireDocker();
        String stackName = "hhprune-" + Long.toHexString(System.nanoTime());
        int stackId = newStack(stackName);
        int keptId = newService(stackId, "kept");
        int droppedId = newService(stackId, "dropped");
        editService(keptId, service -> service.setRecords(StackServiceModel.MOUNTS,
            List.of(mount(StackServiceModel.MOUNT_VOLUME, "keep", "/keep"))));

        runtime.deploy(stackId, "both");
        String keptContainer = containerOf(keptId);
        String droppedContainer = containerOf(droppedId);
        assertThat(docker.exec(keptContainer,
            List.of("sh", "-c", "echo survived > /keep/marker")).exitCode())
            .as("step 1: the volume is writable").isEqualTo(0);

        editService(droppedId, service -> service.set(StackServiceModel.ENABLED, false));
        runtime.deploy(stackId, "reduced");

        assertThatThrownBy(() -> docker.inspectContainer(droppedContainer))
            .as("step 2: the disabled service's workload must not survive the redeploy")
            .isInstanceOf(IOException.class);
        DockerClient.ExecResult marker = docker.exec(containerOf(keptId),
            List.of("cat", "/keep/marker"));
        assertThat(marker.exitCode()).as("step 3: the named volume survived").isEqualTo(0);
        assertThat(marker.stdout()).contains("survived");
    }

    /**
     * A dependency gated on HEALTH whose target declares no healthcheck used to wait out
     * its whole two-minute deadline and then fail with a timeout. It is a named refusal
     * before any waiting: the daemon reports NONE for such a container, and reading NONE
     * as healthy would be a gate that always passes.
     */
    @Test
    void aHealthGateOnAServiceWithoutAHealthcheckRefusesByName() throws IOException {
        requireDocker();
        String stackName = "hhnohc-" + Long.toHexString(System.nanoTime());
        int stackId = newStack(stackName);
        newService(stackId, "base");
        int followerId = newService(stackId, "follower");
        Row depends = new Row();
        depends.set(StackServiceModel.DEPENDS_SERVICE, "base");
        depends.set(StackServiceModel.DEPENDS_CONDITION, StackServiceModel.CONDITION_HEALTHY);
        editService(followerId, service ->
            service.setRecords(StackServiceModel.DEPENDS_ON, List.of(depends)));

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> runtime.deploy(stackId, "nohealth"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("declares")
            .hasMessageContaining("no health check");
        assertThat(System.currentTimeMillis() - started)
            .as("the refusal is immediate, not a two-minute timeout")
            .isLessThan(60_000);
    }

    /**
     * A bind address that is neither the whole host nor loopback cannot be expressed by
     * the instance contract's publication, and is refused BY NAME -- never published
     * somewhere nothing declared.
     */
    @Test
    void anUnsupportedPublicationBindIsRefusedByName() throws IOException {
        requireDocker();
        String stackName = "hhbind-" + Long.toHexString(System.nanoTime());
        int stackId = newStack(stackName);
        int serviceId = newService(stackId, "web");
        editService(serviceId, service -> service.setRecords(StackServiceModel.PORTS,
            List.of(port(80, 34599, "tcp", "10.11.12.13"))));

        assertThatThrownBy(() -> runtime.deploy(stackId, "weird-bind"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("10.11.12.13");
        String container = containerOf(serviceId);
        assertThatThrownBy(() -> docker.inspectContainer(container))
            .as("nothing was created for the refused service")
            .isInstanceOf(IOException.class);
    }

    /**
     * Destroy is VERIFIED and complete: every workload gone at the daemon, the shared
     * link network and its kernel chains gone, the stack-scoped volumes gone.
     */
    @Test
    void destroyRemovesWorkloadsSharedNetworkKernelChainsAndVolumes() throws IOException {
        requireDocker();
        String stackName = "hhdest-" + Long.toHexString(System.nanoTime());
        int stackId = newStack(stackName);
        int serviceId = newService(stackId, "app");
        editService(serviceId, service -> service.setRecords(StackServiceModel.MOUNTS,
            List.of(mount(StackServiceModel.MOUNT_VOLUME, "gone", "/gone"))));

        runtime.deploy(stackId, "before-destroy");
        String container = containerOf(serviceId);
        String linkNetwork = WorkloadNetworks.networkName(StackInstances.networkHandle(stackName));
        String volume = StackInstances.networkHandle(stackName) + "-gone";
        assertThat(docker.inspectVolume(volume)).as("step 1: the volume exists").isNotNull();

        runtime.destroy(stackId, true);

        assertThatThrownBy(() -> docker.inspectContainer(container))
            .as("step 2: the workload is gone").isInstanceOf(IOException.class);
        assertThat(docker.findNetworkByName(linkNetwork))
            .as("step 2: the shared network is gone").isNull();
        assertThatThrownBy(() -> docker.inspectVolume(volume))
            .as("step 2: the stack-scoped volume is gone").isInstanceOf(IOException.class);
        assertThat(netns.inHost("nft", "list", "ruleset").stdout())
            .as("step 2: and its kernel chains went with it")
            .doesNotContain(WorkloadNetworkPolicy.forwardChain(
                WorkloadNetworkPolicy.chainKey(linkNetwork)));
    }

}
