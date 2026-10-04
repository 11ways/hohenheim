package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.database.DatabaseContainerKind;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A configuration writer of an existing instance never puts back the operation-owned columns it loaded: an operation
 * that moved the record on between that load and the write keeps its status and its claim fence.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
class EngineConfigurationSaveTest {

    /** What runs inside the next instance write, between its load and its statement: the winning operation. */
    private static final AtomicReference<Runnable> DURING_WRITE = new AtomicReference<>();

    static {
        InstanceModel.SCHEMA.addBeforeWriteHook(context -> {
            Runnable during = DURING_WRITE.getAndSet(null);
            if (during != null) {
                during.run();
            }
        });
    }

    @Test
    void configurationWritersLeaveTheWinningOperationsStatusAndFenceJourney() throws Exception {
        TestDatabases.freshDatabase();
        SqlDatasource datasource = HohenheimDatabase.datasource();
        HohenheimTestRuntime.ensureBooted();
        Db.run(datasource, () -> HostFixtures.makeLocalPlaceable(16384));
        new DatabaseService(datasource).insertRecord("cfgsave", ManagedDatabase.Engine.MONGO, null,
            "cfguser", "cfgpassword", "cfgdb", false, ServerService.LOCAL_HOST_NAME,
            ResourceLimits.none(), DatabaseModel.STATUS_PROVISIONING);
        int[] ids = new int[2];
        EngineHost[] host = new EngineHost[1];
        Db.run(datasource, () -> {
            Row record = Models.get(DatabaseModel.class).findByName("cfgsave");
            Row engine = Models.get(DatabaseEngineModel.class).findById(record.get(DatabaseModel.ENGINE_ID));
            host[0] = EngineHost.ofEngine(engine);
            ids[0] = DatabaseInstances.ownedBy(host[0]).get(InstanceModel.ID);
            ids[1] = engine.get(DatabaseEngineModel.ID);
        });
        int instanceId = ids[0];
        int engineId = ids[1];

        OwnedInstances.inScope(DatabaseInstances.SOURCE, DatabaseEngineModel.MODEL_ID, engineId, () -> {
            Db.run(datasource, () -> {
                // 1. The engine converges its instance row while an operation finishes on it: the row was loaded
                //    running under fence 7, and the operation stopped it under fence 8 before the write.
                outcome(instanceId, InstanceModel.STATUS_RUNNING, 7L);
                DURING_WRITE.set(() -> outcome(instanceId, InstanceModel.STATUS_STOPPED, 8L));
                try {
                    DatabaseInstances.reserveEngineRow(host[0]);
                } catch (Exception refused) {
                    throw new IllegalStateException(refused);
                }
                assertThat(DURING_WRITE.get()).as("step 1: the operation ran inside the write").isNull();
                assertThat(List.of(status(instanceId), fence(instanceId)))
                    .as("step 1: the winning operation's status and fence stand")
                    .containsExactly(InstanceModel.STATUS_STOPPED, 8L);

                // 2. The boot backfill seals a legacy plaintext environment the same way.
                Row instance = Models.get(InstanceModel.class).findById(instanceId);
                Map<String, Object> settings = new LinkedHashMap<>();
                if (instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> stored) {
                    stored.forEach((key, value) -> settings.put(String.valueOf(key), value));
                }
                settings.put(DatabaseContainerKind.ENVIRONMENT_VARIABLES.getName(),
                    Map.of("MONGO_INITDB_ROOT_PASSWORD", "legacy-plaintext"));
                instance.set(InstanceModel.SETTINGS, settings);
                Models.get(InstanceModel.class).save(instance);
                outcome(instanceId, InstanceModel.STATUS_RUNNING, 9L);
                DURING_WRITE.set(() -> outcome(instanceId, InstanceModel.STATUS_STOPPED, 10L));
                assertThat(DatabaseInstances.sealPlaintextEnvironments()).as("step 2: the legacy row is sealed")
                    .isEqualTo(1);
                assertThat(DURING_WRITE.get()).as("step 2: the operation ran inside the write").isNull();
                assertThat(List.of(status(instanceId), fence(instanceId)))
                    .as("step 2: the winning operation's status and fence stand")
                    .containsExactly(InstanceModel.STATUS_STOPPED, 10L);
                assertThat(InstanceModel.settingsOf(Models.get(InstanceModel.class).findById(instanceId)))
                    .as("step 2: and the configuration change itself landed")
                    .doesNotContainKey(DatabaseContainerKind.ENVIRONMENT_VARIABLES.getName());
            });
            return null;
        });
    }

    /** An operation's outcome, as its fenced statement leaves the row. */
    private static void outcome(int instanceId, String status, long fence) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.STATUS, status)
            .assign(InstanceModel.CLAIM_FENCE, fence)
            .updateAll();
    }

    private static Object status(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS);
    }

    private static Object fence(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.CLAIM_FENCE);
    }
}
