package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.database.DatabaseContainerKind;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
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

/**
 * A database engine's environment (root user, init database and passwords) is stored only in
 * its instance's encrypted SECRET variables, never in {@code instances.settings}: a new engine
 * row carries none, and the boot backfill seals the rows an older controller wrote while the
 * stored secret variables keep winning over the stale settings copy.
 *
 * Hermetic: records in a fresh SQLite, no daemon (the reservation half is daemon-free).
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class DatabaseEngineEnvironmentSealTest {

    private static final String LEGACY_USER = "legacy-root-user";
    private static final String STALE_PASSWORD = "stale-plaintext-password";
    private static final String CURRENT_PASSWORD = "current-secret-password";

    @Test
    void theEngineEnvironmentLivesInSecretVariablesAndLegacyRowsAreSealed() throws Exception {
        File databaseFile = TestDatabases.freshDatabase();
        SqlDatasource datasource = HohenheimDatabase.datasource();
        HohenheimTestRuntime.ensureBooted();
        Db.run(datasource, () -> HostFixtures.makeLocalPlaceable(16384));

        // 1. A shared mongo record mints its engine and reserves the engine instance: the
        //    settings carry no environment at all.
        new DatabaseService(datasource).insertRecord("envseal", ManagedDatabase.Engine.MONGO, null,
            "envuser", "envpassword", "envdb", false, ServerService.LOCAL_HOST_NAME,
            ResourceLimits.none(), DatabaseModel.STATUS_PROVISIONING);
        int[] ids = new int[2];
        Db.run(datasource, () -> {
            Row record = Models.get(DatabaseModel.class).findByName("envseal");
            Row engine = Models.get(DatabaseEngineModel.class).findById(record.get(DatabaseModel.ENGINE_ID));
            EngineHost host = EngineHost.ofEngine(engine);
            Row instance = DatabaseInstances.ownedBy(host);
            assertThat(instance).as("step 1: the engine owns an instance").isNotNull();
            ids[0] = instance.get(InstanceModel.ID);
            ids[1] = engine.get(DatabaseEngineModel.ID);
            assertThat(settingsOf(instance))
                .as("step 1: the reserved settings carry no environment")
                .doesNotContainKey(DatabaseContainerKind.ENVIRONMENT_VARIABLES.getName());

            // 2. What the deploy writes into the secret lane is the WHOLE environment: the
            //    root user and init database as well as the password.
            Map<String, String> environment = DatabaseInstances.engineEnvironment(host);
            assertThat(environment)
                .as("step 2: the root user rides the secret lane")
                .containsEntry("MONGO_INITDB_ROOT_USERNAME", host.rootUser())
                .as("step 2: and so does the root password")
                .containsEntry("MONGO_INITDB_ROOT_PASSWORD", host.rootPassword());
        });
        int instanceId = ids[0];
        int engineId = ids[1];

        // 3. The production shape of an older controller: the settings hold the environment in
        //    the clear (with a password since rotated), the secret lane the current password.
        OwnedInstances.inScope(DatabaseInstances.SOURCE, DatabaseEngineModel.MODEL_ID, engineId, () -> {
            Db.run(datasource, () -> {
                Row instance = Models.get(InstanceModel.class).findById(instanceId);
                Map<String, Object> settings = settingsOf(instance);
                settings.put(DatabaseContainerKind.ENVIRONMENT_VARIABLES.getName(), Map.of(
                    "MONGO_INITDB_ROOT_USERNAME", LEGACY_USER,
                    "MONGO_INITDB_DATABASE", "legacydb",
                    "MONGO_INITDB_ROOT_PASSWORD", STALE_PASSWORD));
                instance.set(InstanceModel.SETTINGS, settings);
                Models.get(InstanceModel.class).save(instance);
                new InstanceVariables().setValue(instanceId, null, "MONGO_INITDB_ROOT_PASSWORD",
                    InstanceVariableModel.KIND_SECRET, CURRENT_PASSWORD);
            });
            return null;
        });

        // 4. The boot backfill seals it: the settings lose the environment and keep the rest,
        //    every value is a secret variable, and the stored secret beats the stale copy.
        Db.run(datasource, () -> {
            assertThat(DatabaseInstances.sealPlaintextEnvironments())
                .as("step 4: the legacy engine instance is sealed").isEqualTo(1);
            Row sealed = Models.get(InstanceModel.class).findById(instanceId);
            assertThat(settingsOf(sealed))
                .as("step 4: the plaintext environment is gone from the settings")
                .doesNotContainKey(DatabaseContainerKind.ENVIRONMENT_VARIABLES.getName())
                .as("step 4: the rest of the settings survive the rewrite")
                .containsEntry("engine", "mongo");
            assertThat(new InstanceVariables().valuesFor(instanceId))
                .as("step 4: the container environment a redeploy builds is unchanged")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                    "MONGO_INITDB_ROOT_USERNAME", LEGACY_USER,
                    "MONGO_INITDB_DATABASE", "legacydb",
                    "MONGO_INITDB_ROOT_PASSWORD", CURRENT_PASSWORD));
            for (Row variable : Models.get(InstanceVariableModel.class).findByInstanceId(instanceId)) {
                assertThat(variable.get(InstanceVariableModel.KIND))
                    .as("step 4: every value is stored SECRET").isEqualTo(InstanceVariableModel.KIND_SECRET);
            }

            // 5. A second boot finds nothing to seal and rewrites nothing.
            List<Object> before = variableIds(instanceId);
            assertThat(DatabaseInstances.sealPlaintextEnvironments())
                .as("step 5: nothing left to seal").isZero();
            assertThat(variableIds(instanceId)).as("step 5: the sealed rows are left alone").isEqualTo(before);
        });

        // 6. At rest neither the settings column nor the variable carrier holds the plaintext.
        assertThat(rawColumn(databaseFile, "SELECT settings FROM instances WHERE id = ?", instanceId))
            .as("step 6: no root user or password in instances.settings")
            .noneMatch(value -> value != null
                && (value.contains(LEGACY_USER) || value.contains(STALE_PASSWORD)));
        assertThat(rawColumn(databaseFile, "SELECT secret_value FROM instance_variables WHERE instance_id = ?",
                instanceId))
            .as("step 6: the secret carrier is ciphertext")
            .isNotEmpty()
            .noneMatch(value -> value != null
                && (value.contains(LEGACY_USER) || value.contains(CURRENT_PASSWORD)));
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
    private static List<String> rawColumn(File databaseFile, String sql, int id) throws Exception {
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
