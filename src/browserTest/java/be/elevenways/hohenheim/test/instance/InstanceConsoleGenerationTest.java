package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A console's late callbacks belong to its own generation: once a redeploy replaced the console, neither its readiness
 * timer nor its exit policy may stamp, release ports for or otherwise act on the deployment that replaced it.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
class InstanceConsoleGenerationTest {

    private static final String HOST = "console-generation-host";

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
    void restore() {
        InstanceConsoles.overrideTimingsForTest(null, null);
        FakeNativeDaemons.resetStreams();
    }

    @Test
    void aReplacedConsolesReadinessTimerLeavesTheNextDeploymentAlone() {
        Db.run(datasource, () -> {
            int instanceId = instanceRecord("generation-readiness", template("generation-readiness", "ready", null));

            // 1. Deployment A waits for its readiness line with a short deadline.
            InstanceConsoles.overrideTimingsForTest(400L, null);
            new InstanceService().deploy(instanceId);
            assertThat(status(instanceId)).as("step 1: A waits for its line").isEqualTo(InstanceModel.STATUS_STARTING);

            // 2. A redeploy replaces A's console before A's deadline; B waits with a long one.
            InstanceConsoles.overrideTimingsForTest(60_000L, null);
            new InstanceService().deploy(instanceId);
            assertThat(status(instanceId)).as("step 2: B waits for its line").isEqualTo(InstanceModel.STATUS_STARTING);

            // 3. A's deadline passes: its timer belongs to a replaced console and fails nothing.
            Poll.never("step 3: the replaced console's timer failed deployment B", Duration.ofMillis(1500),
                () -> InstanceModel.STATUS_ERROR.equals(status(instanceId)));
            assertThat(status(instanceId)).as("step 3: B still waits for its own line")
                .isEqualTo(InstanceModel.STATUS_STARTING);

            // 4. B's own line still flips B to running: only the replaced generation went quiet.
            FakeNativeDaemons.CONSOLE_STREAMS.get(FakeNativeDaemons.handleOf(instanceId)).push("ready\n");
            Poll.until("step 4: B's readiness line flips B to running", Duration.ofSeconds(10),
                () -> InstanceModel.STATUS_RUNNING.equals(status(instanceId)));
        });
    }

    @Test
    void aReplacedConsolesExitLeavesTheReplacementsStatusAndPortsAlone() {
        Db.run(datasource, () -> {
            int instanceId = instanceRecord("generation-exit", template("generation-exit", null, "stop"));
            InstanceOperationLock claims = InstanceOperationLock.of(HostLeases.production());

            // 1. Deployment A runs with a console watch (its stop command).
            new InstanceService().deploy(instanceId);
            String handle = FakeNativeDaemons.handleOf(instanceId);
            FakeNativeDaemons.FakeWorkload workload = FakeNativeDaemons.daemonOf(hostId).get(handle);
            FakeNativeDaemons.ScriptedStream consoleA = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(consoleA).as("step 1: A's console is attached").isNotNull();

            claims.exclusive(instanceId, InstanceOperationLock.Contention.REFUSE, () -> {
                // 2. While an operation holds the record, A's workload exits; A's exit policy waits for the claim.
                workload.running = false;
                consoleA.endFromDaemon();
                Poll.until("step 2: A's exit policy waits for the record's claim", Duration.ofSeconds(10),
                    () -> claims.isQueued(instanceId));

                // 3. The operation redeploys (B) and B observes its published port.
                new InstanceService().deploy(instanceId);
                PortLedger.recordObservedAll(hostId, "127.0.0.1", List.of(47011), "tcp", InstanceModel.MODEL_ID,
                    instanceId, null);
            });
            assertThat(status(instanceId)).as("step 3: B runs").isEqualTo(InstanceModel.STATUS_RUNNING);

            // 4. A's exit policy gets the claim after B: it is not this deployment's, so it touches nothing.
            Poll.never("step 4: the replaced console's exit stopped B or released B's port", Duration.ofMillis(2000),
                () -> !InstanceModel.STATUS_RUNNING.equals(status(instanceId))
                    || PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId).isEmpty());
            assertThat(claims.isQueued(instanceId)).as("step 4: and it is no longer waiting").isFalse();
        });
    }

    private static @Nullable String status(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS);
    }

    private static int template(String name, @Nullable String readinessLine, @Nullable String stopCommand) {
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.DESCRIPTION, "console generation fixture");
        row.set(InstanceTemplateModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceTemplateModel.VERSION, 1);
        if (readinessLine != null) {
            row.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.CONSOLE_LINE.token());
            row.set(InstanceTemplateModel.READINESS_LINE, readinessLine);
        }
        row.set(InstanceTemplateModel.STOP_COMMAND, stopCommand);
        Models.get(InstanceTemplateModel.class).save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static int instanceRecord(String name, int templateId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        row.set(InstanceModel.TEMPLATE_ID, templateId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
