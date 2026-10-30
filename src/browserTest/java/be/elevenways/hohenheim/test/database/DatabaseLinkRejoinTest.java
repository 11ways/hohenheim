package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.EngineHost;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler.Verdict;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.ServedApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database container REPLACED by a lane outside the database tier (the reconciler's crash restart, an operator
 * restart) rejoins the link network of every workload attached to the databases it serves.
 *
 * AIDEV-NOTE: the defect this exists for (Robbedoes, 2026-10-30): the shared Mongo engine died, the reconciler
 * redeployed it through the generic instance deploy, and the fresh container sat on its own network only. Only
 * DatabaseEngines' own lanes rejoined the link networks, so six apps failed to resolve the engine for 8 hours. The
 * fake daemon drops a recreated container from every link network, as a real daemon does, which is what makes a
 * missing rejoin visible here; the kernel policy of those networks remains the live lane's to prove.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class DatabaseLinkRejoinTest {

    /** Bounded wait: the crash restart runs on its own thread. */
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final String PREFIX = "rejoin-";

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
        daemon = new FakeDockerDaemon();
        daemon.install();
        daemon.installContainerKind();
        daemon.installDatabaseKind();
        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            HostFixtures.makeLocalPlaceable(16L * 1024);
            HostFixtures.giveLocalVolumeQuota();
        });
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
    void aReplacedSharedEngineRejoinsEveryAttachedWorkloadsLinkNetwork() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();

            // 1. A shared mongo engine, its container deployed through the instance contract.
            Row engine = save(Models.get(DatabaseEngineModel.class), Map.of(
                DatabaseEngineModel.NAME.getName(), PREFIX + "mongo",
                DatabaseEngineModel.ENGINE.getName(), ManagedDatabase.Engine.MONGO.token(),
                DatabaseEngineModel.ROOT_USER.getName(), "root",
                DatabaseEngineModel.ROOT_PASSWORD.getName(), "root-secret-password-1",
                DatabaseEngineModel.SERVER_ID.getName(), ServerModel.localServerId(),
                DatabaseEngineModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
            int engineId = engine.get(DatabaseEngineModel.ID);
            String engineHandle = EngineHandles.plantEngine(engineId, PREFIX + "mongo",
                ManagedDatabase.Engine.MONGO.token(), InstanceModel.STATUS_RUNNING);
            int engineInstance = DatabaseInstances.ownedBy(EngineHost.ofEngine(engine)).get(InstanceModel.ID);
            service.deploy(engineInstance);
            assertThat(daemon.isRunning(engineHandle)).as("step 1: the engine runs").isTrue();

            // 2. A logical database on it, attached to a plain workload that deploys: the workload's link network
            //    holds exactly the workload and the engine.
            Row database = save(Models.get(DatabaseModel.class), Map.of(
                DatabaseModel.NAME.getName(), PREFIX + "shop",
                DatabaseModel.ENGINE.getName(), ManagedDatabase.Engine.MONGO.token(),
                DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_SHARED,
                DatabaseModel.ENGINE_ID.getName(), engineId,
                DatabaseModel.DB_NAME.getName(), "shopdb", DatabaseModel.DB_USER.getName(), "shopuser",
                DatabaseModel.DB_PASSWORD.getName(), "shop-secret-password-1",
                DatabaseModel.SERVER_ID.getName(), ServerModel.localServerId(),
                DatabaseModel.STATUS.getName(), DatabaseModel.STATUS_ACTIVE));
            int databaseId = database.get(DatabaseModel.ID);
            assertThat(DatabaseInstances.servedBy(Models.get(InstanceModel.class).findById(engineInstance)))
                .as("step 2: the engine instance serves the logical database")
                .containsExactly(databaseId);
            int appId = container(PREFIX + "app");
            save(Models.get(InstanceDatabaseModel.class), Map.of(
                InstanceDatabaseModel.INSTANCE_ID.getName(), appId,
                InstanceDatabaseModel.DATABASE_ID.getName(), databaseId,
                InstanceDatabaseModel.ENV_PREFIX.getName(), "DB"));
            service.deploy(appId);
            String appHandle = FakeDockerDaemon.handleOf(appId);
            Set<String> links = daemon.linkNetworksOf(appHandle);
            assertThat(links).as("step 2: the workload joined one link network").hasSize(1);
            String link = links.iterator().next();
            assertThat(daemon.linkMembersOf(link)).as("step 2: and the engine is its other member")
                .containsExactlyInAnyOrder(appHandle, engineHandle);

            // 3. THE INCIDENT: the engine dies with nobody watching, the reconciler corrects the record and its
            //    restart policy redeploys the engine through the generic instance deploy.
            int startsBefore = daemon.callCount("start:" + engineHandle);
            daemon.killWorkload(engineHandle);
            assertThat(new InstanceStatusReconciler().reconcile(engineInstance).verdict())
                .as("step 3: the reconciler corrects the dead engine").isEqualTo(Verdict.CORRECTED);
            Poll.until("step 3: the crash restart started the engine again", WAIT,
                () -> daemon.callCount("start:" + engineHandle) > startsBefore && daemon.isRunning(engineHandle));
            Poll.until("step 3: the crash restart settled", WAIT,
                () -> new InstanceStatusReconciler().reconcile(engineInstance).verdict() == Verdict.CONFIRMED);
            assertThat(daemon.linkMembersOf(link))
                .as("step 3: the restarted engine is back on the workload's link network")
                .containsExactlyInAnyOrder(appHandle, engineHandle);

            // 4. An operator restart replaces the container as well, and rejoins as well.
            int startsBeforeRestart = daemon.callCount("start:" + engineHandle);
            service.restart(engineInstance);
            assertThat(daemon.callCount("start:" + engineHandle))
                .as("step 4 anchor: the restart recreated and started the engine").isGreaterThan(startsBeforeRestart);
            assertThat(daemon.linkMembersOf(link))
                .as("step 4: the restarted engine is back on the workload's link network")
                .containsExactlyInAnyOrder(appHandle, engineHandle);

            // 5. The engine is dropped from the network outside any deploy lane: the repair sweep joins it again and
            //    says it did, and a sweep over healthy networks joins nothing.
            daemon.disconnectOutside(link, engineHandle);
            assertThat(daemon.linkMembersOf(link)).as("step 5 anchor: the engine is off the network")
                .containsExactly(appHandle);
            assertThat(InstanceDatabaseNetworks.repairAll()).as("step 5: the sweep repaired one membership")
                .isEqualTo(1);
            assertThat(daemon.linkMembersOf(link)).as("step 5: the engine is back on the workload's link network")
                .containsExactlyInAnyOrder(appHandle, engineHandle);
            assertThat(InstanceDatabaseNetworks.repairAll()).as("step 5: a healthy network needs no repair")
                .isZero();
        });
    }

    /** A plain docker-container record with an ephemeral loopback publication. */
    private static int container(String name) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", "v1");
        settings.put("container_port", 8080);
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, name);
        instance.set(InstanceModel.KIND, DockerContainerKind.ID.toString());
        instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instance.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(instance);
        return instance.get(InstanceModel.ID);
    }

    private static Row save(Model model, Map<String, Object> values) {
        Row row = model.createEmptyRow();
        values.forEach(row::set);
        model.save(row);
        return row;
    }
}
