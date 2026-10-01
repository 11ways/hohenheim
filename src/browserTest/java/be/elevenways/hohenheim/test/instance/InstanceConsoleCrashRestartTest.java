package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.Accountability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A crash restart is the SYSTEM's reaction, whoever opened the console session that observed the crash: a
 * CONSOLE-only viewer's session restarts the workload and the restart is attributed to the system, never refused
 * as that viewer's missing POWER.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class InstanceConsoleCrashRestartTest {

    /** Bounded wait: the exit policy runs on the console pump thread. */
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final String HOST = "console-crash-host";

    private static SqlDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = HostFixtures.admittedIncusHost(HOST));
    }

    @AfterEach
    void closeStreams() {
        FakeNativeDaemons.resetStreams();
    }

    @Test
    void aCrashObservedThroughAConsoleOnlyViewersSessionRestartsAsTheSystem() {
        Db.run(datasource, () -> {
            // 1. A running workload with no console watch of its own: no restart policy yet.
            int instanceId = instanceRecord("crash-restart");
            new InstanceService().deploy(instanceId);
            String handle = FakeNativeDaemons.handleOf(instanceId);
            FakeNativeDaemons.FakeWorkload workload = FakeNativeDaemons.daemonOf(hostId).get(handle);
            assertThat(workload.running).as("step 1: the workload runs").isTrue();
            assertThat(FakeNativeDaemons.CONSOLE_STREAMS.get(handle))
                .as("step 1: nothing attached a console yet").isNull();

            // 2. The operator turns on the restart policy; the running workload keeps its (absent) watch.
            Row row = Models.get(InstanceModel.class).findById(instanceId);
            row.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
            Models.get(InstanceModel.class).save(row);

            // 3. A tenant holding CONSOLE and not POWER opens the console: THIS session is the watch.
            int viewerId = ApiSupport.user("crash-viewer@hohenheim.local", "Crash Viewer");
            UserPrincipal viewer = new UserPrincipal(viewerId, "Crash Viewer");
            RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.CONSOLE, true);
            assertThat(List.of(
                    HohenheimAccess.hasInstanceCapability(viewer, instanceId, HohenheimAccess.CONSOLE),
                    HohenheimAccess.hasInstanceCapability(viewer, instanceId, HohenheimAccess.POWER)))
                .as("step 3: the viewer may watch the console and may not power the instance")
                .containsExactly(true, false);
            TenantConduits.as(viewer, () -> InstanceConsoles.subscribe(instanceId, chunk -> { }));
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(stream).as("step 3: the viewer's attach opened the session").isNotNull();
            int deploysBefore = deploys(instanceId).size();

            // 4. The workload dies with no observed stop: the daemon ends the stream in order.
            workload.running = false;
            stream.endFromDaemon();

            // 5. The restart policy redeployed it, as the system and not as the viewer.
            Poll.until("step 5: the crash restart deployed the workload again", WAIT,
                () -> deploys(instanceId).size() > deploysBefore && workload.running);
            assertThat((String) Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS))
                .as("step 5: the record is running again, never left down by a refused restart")
                .isEqualTo(InstanceModel.STATUS_RUNNING);
            Row restart = deploys(instanceId).get(0);
            assertThat(Map.of(
                    "actor", String.valueOf((Object) restart.get(ActivityModel.ACTOR)),
                    "origin", String.valueOf((Object) restart.get(ActivityModel.ORIGIN))))
                .as("step 5: the restart is the system's action, never the viewer's")
                .isEqualTo(Map.of("actor", "null", "origin", Accountability.ORIGIN_SYSTEM));
        });
    }

    private static List<Row> deploys(int instanceId) {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .where(ActivityModel.ACTION.eq(HohenheimActivityAction.DEPLOYED.id().toString()))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .all();
    }

    private static int instanceRecord(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
