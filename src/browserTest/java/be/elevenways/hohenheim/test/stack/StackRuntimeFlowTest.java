package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.migration.M042_CreateStacks;
import be.elevenways.hohenheim.migration.M043_StackUniqueKeys;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
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

    private Integer stackId;

    @BeforeAll
    static void setUp() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-stack-enc").resolve("keys.dry")));

        File db = File.createTempFile("hohenheim-stack-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner((MigrationCapableDatasource) datasource,
            List.of(M042_CreateStacks::new, M043_StackUniqueKeys::new)).migrate();
        HohenheimTestRuntime.ensureBooted();

        docker = new DockerClient();
        runtime = new StackRuntime(docker, datasource);
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
            stack.set(StackModel.SERVER_NAME, "local");
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

    private String containerRole(String stackName) throws IOException {
        DockerClient.ExecResult env = docker.exec("hohenheim-stack-" + stackName + "-app",
            List.of("sh", "-c", "echo $ROLE"));
        return env.stdout().trim();
    }

    @Test
    void deployRedeployRollbackAndStop() throws IOException {
        requireDocker();
        String stackName = "hhflow-" + Long.toHexString(System.nanoTime());
        stackId = createStackRecords(stackName, "one");

        // Deploy from records.
        runtime.deploy(stackId, "test");
        assertThat(containerRole(stackName)).isEqualTo("one");

        Db.run(datasource, () -> {
            Row stack = Models.get(StackModel.class).findById(stackId);
            assertThat(stack.get(StackModel.STATUS)).isEqualTo(StackModel.STATUS_ACTIVE);

            // The deployment row carries an encrypted snapshot with the file content.
            Row deployment = Models.get(StackDeploymentModel.class).findLatestSuccessful(stackId);
            assertThat(deployment).isNotNull();
            assertThat((String) deployment.get(StackDeploymentModel.LOG)).contains("Deploying service 'app'");
        });

        // The config file (with secret content) landed in the container.
        DockerClient.ExecResult config = docker.exec("hohenheim-stack-" + stackName + "-app",
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
        assertThat(containerRole(stackName)).isEqualTo("two");

        // Roll back: the PREVIOUS successful snapshot (role=two is newest, so roll
        // back re-deploys the newest successful spec -- which is role=two; to prove
        // snapshot-based rollback restores OLD config, change records to three
        // WITHOUT deploying, then roll back and expect two (records ignored).
        setServiceRole(stackId, "three");
        runtime.rollback(stackId);
        assertThat(containerRole(stackName)).isEqualTo("two");

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
