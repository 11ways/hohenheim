package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An application whose volume is exclusive swaps releases stop-before-start: the prior release is stopped while it
 * still serves, so its drain finds it already settled, and the drain still frees its port claims.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class ExclusiveVolumeReleaseDrainTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
        daemon = new FakeDockerDaemon();
        daemon.install();
        FakeHostVolumes.install();
        savedProbeTimeout = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 5);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll
    static void tearDown() {
        FakeHostVolumes.uninstall();
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
    }

    @Test
    void anExclusiveVolumeSwapStopsThePriorReleaseFirstAndItsDrainStillFreesItsPorts() {
        Db.run(datasource, () -> {
            setBackend(VolumeBackend.BTRFS);
            int applicationId = application("exclusive-drain", "v1");
            InstanceVolumes.declare(applicationId, "data", "/data", null, true);
            try {
                // 1. v1 serves and holds its port.
                ApplicationReleases.converge(applicationId, Map.of());
                int first = ApplicationReleases.ownedServing(applicationId).get(InstanceModel.ID);
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, first)).as("step 1: v1 holds a port")
                    .isNotEmpty();

                // 2. On a host whose volumes cannot be snapshot, v2 is refused before its candidate exists: the
                //    exclusive swap snapshots first, so v1 is started again and keeps serving. This is why a converge
                //    on such a host leaves the same serving release (the earlier draft's "kept id 9").
                setBackend(VolumeBackend.NONE);
                setSettings(applicationId, "v2");
                ApplicationReleases.converge(applicationId, Map.of());
                assertThat((Object) ApplicationReleases.ownedServing(applicationId).get(InstanceModel.ID))
                    .as("step 2: the refused v2 leaves v1 serving").isEqualTo(first);
                Row refused = latestOp(applicationId);
                assertThat(java.util.List.of(String.valueOf((Object) refused.get(ReleaseOperationModel.STATUS)),
                        String.valueOf((Object) refused.get(ReleaseOperationModel.FAILURE_REASON)).contains("snapshot")))
                    .as("step 2: its operation failed, naming the snapshot")
                    .containsExactly(ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.FAILED), true);

                // 3. On a host that snapshots, v2 swaps in: v1 was stopped BEFORE the candidate started.
                setBackend(VolumeBackend.BTRFS);
                ApplicationReleases.converge(applicationId, Map.of());
                int second = ApplicationReleases.ownedServing(applicationId).get(InstanceModel.ID);
                assertThat(second).as("step 3: a new release serves").isNotEqualTo(first);
                Row swap = latestOp(applicationId);
                assertThat(String.valueOf((Object) swap.get(ReleaseOperationModel.STEP_LOG)))
                    .as("step 3: the swap stopped the prior release first")
                    .contains("exclusive volume: stopping prior release");

                // 4. THE ASSERTION: the drain finds v1 already settled and still frees its port claims.
                Poll.until("step 4: the swap's drain completes", Duration.ofSeconds(30), Duration.ofMillis(20),
                    () -> ReleaseOperationModel.LIFECYCLE.is(latestOp(applicationId)
                        .get(ReleaseOperationModel.STATUS), OperationStatus.SUCCEEDED));
                assertThat(String.valueOf((Object) latestOp(applicationId).get(ReleaseOperationModel.STEP_LOG)))
                    .as("step 4: the drain found v1 already settled").contains("superseded release already settled");
                Row retired = Models.get(InstanceModel.class).findById(first);
                assertThat(java.util.List.of(String.valueOf((Object) retired.get(InstanceModel.RUNTIME_ROLE)),
                        String.valueOf((Object) retired.get(InstanceModel.STATUS))))
                    .as("step 4: v1 is the retired, stopped rollback target")
                    .containsExactly(InstanceModel.ROLE_RETIRED, InstanceModel.STATUS_STOPPED);
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, first))
                    .as("step 4: and holds no port claim").isEmpty();
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, second))
                    .as("step 4: while v2 holds one").isNotEmpty();
            } finally {
                ApplicationReleases.destroyFor(applicationId);
                setBackend(VolumeBackend.UNCHECKED);
            }
        });
    }

    /**
     * A throw AFTER the switch of an exclusive-volume app keeps the new release serving: the gate's catch used to
     * destroy the candidate that had just taken the traffic and start the prior writer again, leaving the app with no
     * serving release at all (and two writers, had the prior one still been there).
     */
    @Test
    void aThrowAfterTheSwitchKeepsTheNewReleaseServing() {
        Db.run(datasource, () -> {
            setBackend(VolumeBackend.BTRFS);
            int applicationId = application("exclusive-after-switch", "v1");
            InstanceVolumes.declare(applicationId, "data", "/data", null, true);
            try {
                // 1. v1 serves.
                ApplicationReleases.converge(applicationId, Map.of());
                int first = ApplicationReleases.ownedServing(applicationId).get(InstanceModel.ID);

                // 2. v2's switch throws after the candidate took the traffic: the prior release vanished under it.
                setSettings(applicationId, "v2");
                daemon.duringNextStart(() -> Models.get(InstanceModel.class).find()
                    .where(InstanceModel.ID.eq(first)).assign(InstanceModel.DELETED_AT, Now.instant()).updateAll());
                assertThat(catchThrowable(() -> ApplicationReleases.converge(applicationId, Map.of())))
                    .as("step 2: the deploy fails after its switch").isNotNull();

                // 3. THE ASSERTION: the candidate is kept serving, its container running.
                Row serving = ApplicationReleases.ownedServing(applicationId);
                assertThat(serving).as("step 3: a release still serves").isNotNull();
                int second = serving.get(InstanceModel.ID);
                assertThat(second).as("step 3: the new one").isNotEqualTo(first);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(second)))
                    .as("step 3: and its container runs").isTrue();
            } finally {
                ApplicationReleases.destroyFor(applicationId);
                setBackend(VolumeBackend.UNCHECKED);
            }
        });
    }

    private static int application(String name, String tag) {
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.HOST_ID, HostModel.localHostId());
        application.set(InstanceModel.SETTINGS, settingsFor(tag));
        Models.get(InstanceModel.class).save(application);
        return application.get(InstanceModel.ID);
    }

    private static void setSettings(int applicationId, String tag) {
        Row application = Models.get(InstanceModel.class).findById(applicationId);
        application.set(InstanceModel.SETTINGS, settingsFor(tag));
        Models.get(InstanceModel.class).save(application);
    }

    private static Map<String, Object> settingsFor(String tag) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", tag);
        settings.put("container_port", 8080);
        return settings;
    }

    private static void setBackend(VolumeBackend backend) {
        Row server = Models.get(HostModel.class).findById(HostModel.localHostId());
        server.set(HostModel.VOLUME_BACKEND, backend.token());
        Models.get(HostModel.class).save(server);
    }

    private static Row latestOp(int applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
    }
}
