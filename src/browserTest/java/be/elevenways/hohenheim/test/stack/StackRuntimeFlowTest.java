package be.elevenways.hohenheim.test.stack;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The full record flow: stack records -> deploy -> encrypted deployment snapshot ->
 * config change -> redeploy -> rollback to the snapshot -> stop. Uses a temp SQLite
 * for records and the real local Docker daemon for containers.
 */
class StackRuntimeFlowTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String TEST_IMAGE = "alpine:latest";

    private static SqliteDatasource datasource;
    private static StackRuntime runtime;
    private static DockerClient docker;

    // The runtime resolves its policy applier through WorkloadNetworkPolicy.forServer,
    // so the netns fixture is installed as the process-wide override -- without it
    // every deploy here would (rightly) refuse on a developer machine.
    private static PrivateNetns netns;

    private Integer stackId;

    @BeforeAll
    static void setUp() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-stack-enc").resolve("keys.dry")));

        File db = File.createTempFile("hohenheim-stack-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
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
        if (stackId != null) {
            try {
                runtime.destroy(stackId, true);
            } catch (Exception ignored) {
                // best effort
            }
            stackId = null;
        }
    }

    private void requireDocker() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assumeTrue(netns != null,
            "no private netns: a stack refuses to deploy where its policy cannot be enforced");
        boolean present = false;
        for (Object image : docker.listImages()) {
            Object tags = ((Map<?, ?>) image).get("RepoTags");
            if (tags instanceof List<?> list && list.contains(TEST_IMAGE)) {
                present = true;
                break;
            }
        }
        assumeTrue(present, TEST_IMAGE + " not present locally");
    }

    private int createStackRecords(String stackName, String roleValue) {
        int[] id = new int[1];
        Db.run(datasource, () -> {
            StackModel stacks = Models.get(StackModel.class);
            Row stack = stacks.createEmptyRow();
            stack.set(StackModel.NAME, stackName);
            stack.set(StackModel.ENABLED, true);
            stack.set(StackModel.SERVER_ID, ServerModel.localServerId());
            stack.set(StackModel.REGISTRY_PASSWORD, "very-private-registry-secret");
            stacks.save(stack);
            id[0] = stack.get(StackModel.ID);

            StackServiceModel services = Models.get(StackServiceModel.class);
            Row service = services.createEmptyRow();
            service.set(StackServiceModel.STACK_ID, id[0]);
            service.set(StackServiceModel.NAME, "app");
            service.set(StackServiceModel.ENABLED, true);
            service.set(StackServiceModel.IMAGE, TEST_IMAGE);
            service.set(StackServiceModel.COMMAND, List.of("sleep", "600"));
            service.set(StackServiceModel.ENVIRONMENT, Map.of("ROLE", roleValue));
            service.set(StackServiceModel.RESTART_POLICY, "no");
            services.save(service);

            StackFileModel files = Models.get(StackFileModel.class);
            Row file = files.createEmptyRow();
            file.set(StackFileModel.STACK_SERVICE_ID, service.get(StackServiceModel.ID));
            file.set(StackFileModel.CONTAINER_PATH, "/etc/hhtest/app.conf");
            file.set(StackFileModel.CONTENT, "token=super-secret-token\n");
            file.set(StackFileModel.MODE, "0600");
            files.save(file);
        });
        return id[0];
    }

    private void setServiceRole(int stackId, String roleValue) {
        Db.run(datasource, () -> {
            StackServiceModel services = Models.get(StackServiceModel.class);
            Row service = services.findByStackId(stackId).get(0);
            service.set(StackServiceModel.ENVIRONMENT, Map.of("ROLE", roleValue));
            services.save(service);
        });
    }

    private String containerRole(int stackId) throws IOException {
        DockerClient.ExecResult env = docker.exec(appContainer(stackId),
            List.of("sh", "-c", "echo $ROLE"));
        return env.stdout().trim();
    }

    /** THE daemon-side container name of the stack's "app" service, via its owned instance. */
    private String appContainer(int stackId) {
        String[] handle = new String[1];
        Db.run(datasource, () -> {
            Row instance = StackInstances.ownedByStack(stackId).values().iterator().next();
            handle[0] = ControllerScope.handle(ControllerScope.KIND_INSTANCE,
                instance.get(InstanceModel.ID));
        });
        return handle[0];
    }

    @Test
    void deployRedeployRollbackAndStop() throws IOException {
        requireDocker();
        String stackName = "hhflow-" + Long.toHexString(System.nanoTime());
        stackId = createStackRecords(stackName, "one");

        // Deploy from records.
        runtime.deploy(stackId, "test");
        assertThat(containerRole(stackId)).isEqualTo("one");

        Db.run(datasource, () -> {
            Row stack = Models.get(StackModel.class).findById(stackId);
            assertThat(stack.get(StackModel.STATUS)).isEqualTo(StackModel.STATUS_ACTIVE);

            // The deployment row carries an encrypted snapshot with the file content.
            Row deployment = Models.get(StackDeploymentModel.class).findLatestSuccessful(stackId);
            assertThat(deployment).isNotNull();
            assertThat((String) deployment.get(StackDeploymentModel.LOG)).contains("Deploying service 'app'");
        });

        // The config file (with secret content) landed in the container.
        DockerClient.ExecResult config = docker.exec(appContainer(stackId),
            List.of("cat", "/etc/hhtest/app.conf"));
        assertThat(config.stdout()).contains("super-secret-token");

        // Secrets never sit in plaintext in the database.
        SqlDatasource sql = datasource.unwrap(SqlDatasource.class);
        for (Row raw : sql.rawQuery("SELECT registry_password FROM stacks WHERE id = ?", stackId)) {
            assertThat(String.valueOf(raw.get("registry_password"))).startsWith("zenc$");
        }
        for (Row raw : sql.rawQuery("SELECT content FROM stack_files")) {
            assertThat(String.valueOf(raw.get("content"))).startsWith("zenc$");
            assertThat(String.valueOf(raw.get("content"))).doesNotContain("super-secret-token");
        }
        for (Row raw : sql.rawQuery(
                "SELECT spec FROM stack_deployments WHERE stack_id = ? AND spec IS NOT NULL", stackId)) {
            assertThat(String.valueOf(raw.get("spec"))).startsWith("zenc$");
            assertThat(String.valueOf(raw.get("spec"))).doesNotContain("super-secret-token");
        }

        // Change config + redeploy: the container is replaced with the new env.
        setServiceRole(stackId, "two");
        runtime.deploy(stackId, "change");
        assertThat(containerRole(stackId)).isEqualTo("two");

        // Roll back: the PREVIOUS successful snapshot (role=two is newest, so roll
        // back re-deploys the newest successful spec -- which is role=two; to prove
        // snapshot-based rollback restores OLD config, change records to three
        // WITHOUT deploying, then roll back and expect two (records ignored).
        setServiceRole(stackId, "three");
        runtime.rollback(stackId);
        assertThat(containerRole(stackId)).isEqualTo("two");

        // Stop: containers halt, status STOPPED.
        runtime.stop(stackId);
        Db.run(datasource, () -> {
            Row stack = Models.get(StackModel.class).findById(stackId);
            assertThat(stack.get(StackModel.STATUS)).isEqualTo(StackModel.STATUS_STOPPED);
        });
        Map<String, String> serviceStates = runtime.serviceStates(stackId);
        assertThat(serviceStates.get("app")).isEqualTo("stopped");
    }

    /**
     * The purge must remove the CONTAINERS too: Docker refuses to remove a volume
     * attached to any container, stopped ones included, so a stop-only purge would
     * fail on every mounted volume. The next deploy rebuilds the stack empty.
     */
    @Test
    void purgeVolumesTearsTheStackDownAndDestroysItsData() throws IOException {
        requireDocker();
        String stackName = "hhflow-purge-" + Long.toHexString(System.nanoTime());
        stackId = createStackRecords(stackName, "one");
        String volume = ControllerScope.handle(ControllerScope.KIND_STACK, stackName) + "-data";

        // 1. Give the service a named volume and deploy.
        Db.run(datasource, () -> {
            StackServiceModel services = Models.get(StackServiceModel.class);
            Row service = services.findByStackId(stackId).get(0);
            Row mount = new Row();
            mount.set(StackServiceModel.MOUNT_TYPE, StackServiceModel.MOUNT_VOLUME);
            mount.set(StackServiceModel.MOUNT_NAME, "data");
            mount.set(StackServiceModel.MOUNT_PATH, "/data");
            service.setRecords(StackServiceModel.MOUNTS, List.of(mount));
            services.save(service);
        });
        runtime.deploy(stackId, "with-volume");
        docker.exec(appContainer(stackId), List.of("sh", "-c", "echo marker > /data/marker.txt"));
        assertThat(volumeExists(volume)).as("step 1: the owned volume exists").isTrue();

        // 2. Purge: container and volume both go, the stack reads inactive.
        //    This is the one stack operation that destroys DATA, so it is also the one
        //    that must be answerable: it records who purged, on the stack record.
        Accountability.runAs(new Accountability("purger", "The purger", null, "junit",
                Accountability.ORIGIN_WEB), () -> {
            try {
                runtime.purgeVolumes(stackId);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(volumeExists(volume)).as("step 2: the owned volume is destroyed").isFalse();
        Db.run(datasource, () -> {
            Row stack = Models.get(StackModel.class).findById(stackId);
            assertThat(stack.get(StackModel.STATUS))
                .as("step 2: nothing of the stack remains")
                .isEqualTo(StackModel.STATUS_INACTIVE);
            List<Row> purged = Models.get(ActivityModel.class).find()
                .where(ActivityModel.MODEL.eq(StackModel.MODEL_ID.toString()))
                .where(ActivityModel.RECORD_ID.eq(String.valueOf(stackId)))
                .where(ActivityModel.ACTION.eq(StackRuntime.ACTIVITY_PURGE_ACTION))
                .all();
            assertThat(purged)
                .withFailMessage("step 2: the volume purge must name who destroyed the"
                    + " data; found %s activity rows", purged.size())
                .hasSize(1);
            assertThat((String) purged.get(0).get(ActivityModel.ACTOR))
                .as("step 2: attributed to the operator, not to the system")
                .isEqualTo("purger");
        });

        // 3. The next deploy rebuilds from the records, with an EMPTY volume.
        runtime.deploy(stackId, "after-purge");
        DockerClient.ExecResult listing = docker.exec(appContainer(stackId), List.of("ls", "/data"));
        assertThat(listing.stdout()).as("step 3: the data did not survive the purge")
            .doesNotContain("marker.txt");
    }

    /**
     * The refusal travels the PRODUCT lane honestly: a deploy on a host that cannot
     * enforce the network policy fails the deploy, stamps the stack FAILED, records the
     * refusal in the deployment log, creates nothing at the daemon -- and the SAME stack
     * deploys cleanly once the host can enforce (the recovery path an operator takes).
     */
    @Test
    void anUnenforceableHostFailsTheDeployVisiblyAndEnforcementRecoversIt() throws IOException {
        requireDocker();
        String stackName = "hhflow-refused-" + Long.toHexString(System.nanoTime());
        stackId = createStackRecords(stackName, "one");
        String network = WorkloadNetworks.networkName(StackInstances.networkHandle(stackName));

        // 1. Enforcement off (the pre-enforcement host): the deploy fails BY NAME.
        WorkloadNetworkPolicy.overrideForTest(
            new WorkloadNetworkPolicy(netns.nftRunner(), () -> false));
        try {
            IOException refusal = null;
            try {
                runtime.deploy(stackId, "unenforceable");
            } catch (IOException expected) {
                refusal = expected;
            }
            assertThat(refusal).as("step 1: the deploy fails").isNotNull();
            // Since the lowering the FIRST unenforceable thing a deploy touches is the
            // service's OWN per-workload network, so that is the name in the refusal --
            // earlier than the shared stack network, which is exactly the right order.
            assertThat(refusal.getMessage())
                .as("step 1: the refusal names the workload and the cause")
                .contains("REFUSED to deploy")
                .contains(WorkloadNetworks.networkName(appContainer(stackId)))
                .contains("security.nftables_enabled");

            // 2. Resulting state: FAILED status, the refusal in the deployment log,
            //    nothing at the daemon.
            Db.run(datasource, () -> {
                Row stack = Models.get(StackModel.class).findById(stackId);
                assertThat(stack.get(StackModel.STATUS))
                    .as("step 2: the stack reads failed, not silently active")
                    .isEqualTo(StackModel.STATUS_FAILED);
                Row deployment = Models.get(StackDeploymentModel.class).find()
                    .where(StackDeploymentModel.STACK_ID.eq(stackId)).first();
                assertThat((String) deployment.get(StackDeploymentModel.LOG))
                    .as("step 2: the operator can read WHY in the deployment log")
                    .contains("REFUSED to deploy");
            });
            assertThat(docker.findNetworkByName(network))
                .as("step 2: no network was created").isNull();
            assertThatThrownBy(() -> docker.inspectContainer(appContainer(stackId)))
                .as("step 2: no container was created")
                .isInstanceOf(IOException.class);
        } finally {
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }

        // 3. The recovery: the host gains enforcement and the SAME records deploy.
        runtime.deploy(stackId, "enforced");
        assertThat(containerRole(stackId)).as("step 3: the stack now runs").isEqualTo("one");
        assertThat(netns.inHost("nft", "list", "ruleset").stdout())
            .as("step 3: and its kernel chains exist")
            .contains(WorkloadNetworkPolicy.forwardChain(WorkloadNetworkPolicy.chainKey(network)));
    }

    private boolean volumeExists(String name) throws IOException {
        for (Object entry : docker.listVolumes()) {
            if (entry instanceof Map<?, ?> volume && name.equals(String.valueOf(volume.get("Name")))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A deploy interrupted by a crash or restart leaves the row claiming "deploying",
     * and refreshStatus defers to a running deploy -- so without a boot sweep that
     * stack is never monitored or alerted on again.
     */
    @Test
    void interruptedDeployStatusIsSweptAtBoot() throws IOException {
        requireDocker();
        String stackName = "hhflow-swept-" + Long.toHexString(System.nanoTime());
        stackId = createStackRecords(stackName, "one");

        runtime.deploy(stackId, "initial");

        // Simulate the crash: the status row is left mid-deploy.
        Db.run(datasource, () -> {
            StackModel stacks = Models.get(StackModel.class);
            Row stack = stacks.findById(stackId);
            stack.set(StackModel.STATUS, StackModel.STATUS_DEPLOYING);
            stacks.save(stack);
        });

        assertThat(runtime.refreshStatus(stackId))
            .as("a deploying claim blocks status refresh, which is why the sweep exists")
            .isEqualTo(StackModel.STATUS_DEPLOYING);

        runtime.resetInterruptedDeploys();

        Db.run(datasource, () -> {
            Row stack = Models.get(StackModel.class).findById(stackId);
            assertThat(stack.get(StackModel.STATUS))
                .as("the sweep recomputes from live containers, which are still running")
                .isEqualTo(StackModel.STATUS_ACTIVE);
        });
    }

    /**
     * The worker lane survives a destroy: retiring it opened a window where a
     * concurrent submission minted a SECOND executor (two threads on one stack's
     * Docker resources) or was rejected after claiming "deploying" -- a permanently
     * wedged status row. A destroy must simply be another serialized operation.
     */
    @Test
    void operationsAfterDestroyStillRunOnTheSameLane() throws IOException {
        requireDocker();
        String stackName = "hhflow-relane-" + Long.toHexString(System.nanoTime());
        stackId = createStackRecords(stackName, "one");

        runtime.deploy(stackId, "initial");
        runtime.destroy(stackId, true);
        Db.run(datasource, () -> {
            Row stack = Models.get(StackModel.class).findById(stackId);
            assertThat(stack.get(StackModel.STATUS)).isEqualTo(StackModel.STATUS_INACTIVE);
        });

        // A deploy AFTER the destroy is legal (the record still exists) and must
        // work on the same serialized lane instead of being rejected.
        runtime.deploy(stackId, "again");
        Db.run(datasource, () -> {
            Row stack = Models.get(StackModel.class).findById(stackId);
            assertThat(stack.get(StackModel.STATUS)).isEqualTo(StackModel.STATUS_ACTIVE);
        });
    }
}
