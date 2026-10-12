package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An exclusive console (Incus admits one per instance, serial and VGA alike) is held only while something needs it:
 * a restart policy alone opens no watch on it, a viewer's session ends with its last viewer, and a command sent with
 * nobody watching closes the console behind it, so the VM's screen and the operator's own console are not kept out.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class ExclusiveConsoleTest {

    private static final String HOST = "exclusive-console-host";

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
    void anExclusiveConsoleIsHeldOnlyWhileSomethingNeedsIt() {
        FakeNativeDaemons.exclusiveConsoles = true;
        Db.run(datasource, () -> {
            // 1. A workload deployed under the restart policy holds no console: the reconciler catches its exit.
            int instanceId = instanceRecord("exclusive");
            new InstanceService().deploy(instanceId);
            String handle = FakeNativeDaemons.handleOf(instanceId);
            assertThat(FakeNativeDaemons.daemonOf(hostId).get(handle).running).as("step 1: the workload runs")
                .isTrue();
            assertThat(FakeNativeDaemons.CONSOLE_STREAMS.get(handle))
                .as("step 1: the restart policy alone attached no console").isNull();

            // 2. A viewer opens the console, and leaving closes it behind it.
            InstanceConsoles.Subscription viewer = InstanceConsoles.subscribe(instanceId, chunk -> { });
            FakeNativeDaemons.ScriptedStream first = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(first).as("step 2: the viewer opened the console").isNotNull();
            viewer.close();
            assertThat(first.isClosed()).as("step 2: the last viewer leaving closed the console").isTrue();
            assertThat(InstanceConsoles.peek(instanceId)).as("step 2: and the hub forgot its session").isNull();

            // 3. Two viewers share one console; it stays open while either still watches, a command included.
            InstanceConsoles.Subscription ann = InstanceConsoles.subscribe(instanceId, chunk -> { });
            InstanceConsoles.Subscription bob = InstanceConsoles.subscribe(instanceId, chunk -> { });
            FakeNativeDaemons.ScriptedStream second = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            ann.close();
            InstanceConsoles.sendCommand(instanceId, "uptime");
            assertThat(second.isClosed()).as("step 3: Bob still watches, so the console stays open").isFalse();
            bob.close();
            assertThat(second.isClosed()).as("step 3: the last of them leaving closed it").isTrue();

            // 4. A command sent with nobody watching reaches the workload and closes the console after it.
            InstanceConsoles.sendCommand(instanceId, "sync");
            FakeNativeDaemons.ScriptedStream third = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(third.stdinWrites()).as("step 4: the line reached the workload").containsExactly("sync\n");
            assertThat(third.isClosed()).as("step 4: and nothing kept the console afterwards").isTrue();
        });
    }

    private static int instanceRecord(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        row.set(InstanceModel.CRASH_POLICY, InstanceModel.CRASH_RESTART);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
