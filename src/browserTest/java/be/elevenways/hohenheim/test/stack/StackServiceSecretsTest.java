package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackServiceKind;
import be.elevenways.hohenheim.server.stack.StackSpec;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A stack service's environment reaches its owned instance as SECRET variable rows and
 * never as a plaintext copy in {@code instances.settings} -- on every deploy, and, for the
 * rows an older controller already wrote in the clear, through the boot backfill.
 *
 * Hermetic: records in a fresh SQLite, the daemon is {@link FakeDockerDaemon}. The DEPLOY's
 * daemon half is not this test's subject (the stack kind's network and kernel policy need a
 * real host, see StackInstancesTest), so a deploy is allowed to fail AFTER the record tier
 * settled; every assertion is on what the record tier stored.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class StackServiceSecretsTest {

    private static final String PASSWORD = "hunter2-stack-secret";

    private static SqlDatasource datasource;
    private static File databaseFile;
    private static FakeDockerDaemon daemon;

    @BeforeAll
    static void setUp() throws Exception {
        databaseFile = TestDatabases.freshDatabase();
        datasource = HohenheimDatabase.datasource();
        HohenheimTestRuntime.ensureBooted();
        daemon = new FakeDockerDaemon();
        daemon.install();
    }

    @AfterAll
    static void tearDown() {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
    }

    @Test
    void aDeployCarriesTheServiceEnvironmentAsSecretsAndDropsARemovedKey() throws Exception {
        int[] ids = new int[2];
        Db.run(datasource, () -> {
            // 1. A stack service whose (encrypted) environment carries a credential.
            int stackId = stack("secenv");
            ids[0] = service(stackId, "web", Map.of("DB_PASSWORD", PASSWORD, "MODE", "prod"));
            deploy(stackId);

            Row instance = StackInstances.owned(ids[0]);
            assertThat(instance).as("step 1: the service owns an instance").isNotNull();
            ids[1] = instance.get(InstanceModel.ID);
            assertThat(settingsOf(instance))
                .as("step 1: the settings map carries no environment")
                .doesNotContainKey(StackServiceKind.ENVIRONMENT_VARIABLES.getName());
            assertThat(new InstanceVariables().valuesFor(ids[1]))
                .as("step 1: the workload still gets every value, through its variables")
                .containsExactlyInAnyOrderEntriesOf(Map.of("DB_PASSWORD", PASSWORD, "MODE", "prod"));
            for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(ids[1])) {
                assertThat(variable.get(InstanceVariableModel.KIND))
                    .as("step 1: every value is stored SECRET").isEqualTo(InstanceVariableModel.KIND_SECRET);
            }

            // 2. The service drops MODE and rotates the password: the redeploy makes the
            //    variables exactly the new environment.
            Row service = Models.get(StackServiceModel.class).findById(ids[0]);
            service.set(StackServiceModel.ENVIRONMENT, Map.of("DB_PASSWORD", PASSWORD + "-rotated"));
            Models.get(StackServiceModel.class).save(service);
            deploy(stackId);
            assertThat(new InstanceVariables().valuesFor(ids[1]))
                .as("step 2: a dropped key stops reaching the workload, a rotated one follows")
                .containsExactlyInAnyOrderEntriesOf(Map.of("DB_PASSWORD", PASSWORD + "-rotated"));
        });

        // 3. At rest, neither the settings column nor the variable rows hold the plaintext.
        assertThat(rawColumn("SELECT settings FROM instances WHERE id = ?", ids[1]))
            .as("step 3: no credential in instances.settings")
            .noneMatch(value -> value != null && value.contains(PASSWORD));
        assertThat(rawColumn("SELECT secret_value FROM instance_variables WHERE instance_id = ?", ids[1]))
            .as("step 3: the secret carrier is ciphertext, not the password")
            .isNotEmpty()
            .noneMatch(value -> value != null && value.contains(PASSWORD));
    }

    @Test
    void theBootBackfillSealsAPlaintextEnvironmentAnOlderControllerWrote() throws Exception {
        int[] instanceId = new int[1];
        Db.run(datasource, () -> {
            // 1. The production shape before this fix: the instance settings carry the
            //    service's environment in the clear.
            int stackId = stack("seclegacy");
            int serviceId = service(stackId, "db", Map.of("DB_PASSWORD", PASSWORD));
            instanceId[0] = legacyInstance(serviceId, stackId, Map.of("DB_PASSWORD", PASSWORD));

            // 2. The boot pass seals it: settings stripped, the value now a secret variable.
            StackInstances.adoptExisting();
            Row sealed = Models.get(InstanceModel.class).findById(instanceId[0]);
            assertThat(settingsOf(sealed))
                .as("step 2: the plaintext environment is gone from the settings")
                .doesNotContainKey(StackServiceKind.ENVIRONMENT_VARIABLES.getName());
            assertThat(settingsOf(sealed))
                .as("step 2: the rest of the settings survive the rewrite")
                .containsEntry(StackServiceKind.SERVICE_NAME.getName(), "db");
            assertThat(new InstanceVariables().valuesFor(instanceId[0]))
                .as("step 2: the value moved into the instance's variables")
                .containsExactlyEntriesOf(Map.of("DB_PASSWORD", PASSWORD));

            // 3. A second boot finds nothing to seal and rewrites nothing.
            List<Object> before = variableIds(instanceId[0]);
            StackInstances.adoptExisting();
            assertThat(variableIds(instanceId[0]))
                .as("step 3: idempotent -- the sealed rows are left alone").isEqualTo(before);
        });

        // 4. At rest the credential is ciphertext only.
        assertThat(rawColumn("SELECT settings FROM instances WHERE id = ?", instanceId[0]))
            .as("step 4: no credential in instances.settings")
            .noneMatch(value -> value != null && value.contains(PASSWORD));
        assertThat(rawColumn("SELECT secret_value FROM instance_variables WHERE instance_id = ?",
                instanceId[0]))
            .as("step 4: the secret carrier is ciphertext")
            .isNotEmpty()
            .noneMatch(value -> value != null && value.contains(PASSWORD));
    }

    // -- fixtures ----------------------------------------------------------------

    private static void deploy(int stackId) {
        StackSpec spec = StackSpec.fromRecords(Models.get(StackModel.class).findById(stackId));
        for (StackSpec.ServiceSpec service : spec.services()) {
            // The daemon half may refuse (see the class note); the record tier has settled.
            catchThrowable(() -> StackInstances.deploy(spec, service));
        }
    }

    private static int stack(String name) {
        StackModel model = Models.get(StackModel.class);
        Row stack = model.createEmptyRow();
        stack.set(StackModel.NAME, name);
        // Disabled: the boot adoption pass must not try to deploy it.
        stack.set(StackModel.ENABLED, false);
        stack.set(StackModel.SERVER_ID, ServerModel.localServerId());
        model.save(stack);
        return stack.get(StackModel.ID);
    }

    private static int service(int stackId, String name, Map<String, String> environment) {
        StackServiceModel model = Models.get(StackServiceModel.class);
        Row service = model.createEmptyRow();
        service.set(StackServiceModel.STACK_ID, stackId);
        service.set(StackServiceModel.NAME, name);
        service.set(StackServiceModel.ENABLED, true);
        service.set(StackServiceModel.IMAGE, "alpine:latest");
        service.set(StackServiceModel.ENVIRONMENT, environment);
        model.save(service);
        return service.get(StackServiceModel.ID);
    }

    /** An owned instance in the pre-fix shape, written in the service's own scope. */
    private static int legacyInstance(int serviceId, int stackId, Map<String, String> environment) {
        try {
            return legacyInstanceScoped(serviceId, stackId, environment);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static int legacyInstanceScoped(int serviceId, int stackId,
                                            Map<String, String> environment) throws Exception {
        return OwnedInstances.inScope(StackInstances.SOURCE, StackServiceModel.MODEL_ID, serviceId,
            () -> {
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put(StackServiceKind.IMAGE.getName(), "alpine:latest");
                settings.put(StackServiceKind.ENVIRONMENT_VARIABLES.getName(), environment);
                settings.put(StackServiceKind.STACK_ID.getName(), stackId);
                settings.put(StackServiceKind.STACK_NETWORK.getName(),
                    StackInstances.networkHandle("seclegacy"));
                settings.put(StackServiceKind.SERVICE_NAME.getName(), "db");
                Row instance = Models.get(InstanceModel.class).createEmptyRow();
                instance.set(InstanceModel.NAME, "seclegacy-db");
                instance.set(InstanceModel.KIND, StackServiceKind.ID.toString());
                instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
                instance.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_NONE);
                instance.set(InstanceModel.SETTINGS, settings);
                Models.get(InstanceModel.class).save(instance);
                return (Integer) instance.get(InstanceModel.ID);
            });
    }

    private static Map<String, Object> settingsOf(Row instance) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map) {
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        }
        return copy;
    }

    private static List<Object> variableIds(int instanceId) {
        List<Object> ids = new ArrayList<>();
        for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(instanceId)) {
            ids.add(variable.get(InstanceVariableModel.ID));
        }
        return ids;
    }

    /** The stored column exactly as it sits on disk, bypassing the ORM's decryption. */
    private static List<String> rawColumn(String sql, int id) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.getAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }
}
