package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An exclusive console (Incus admits one per instance, serial and VGA alike) is held only while something needs it:
 * a restart policy alone opens no watch on it, a viewer's session ends with its last viewer, a command sent with
 * nobody watching closes the console behind it, a readiness watch lets it go once the line is seen, and a stop command
 * opens it only for the stop, so the VM's screen and the operator's own console are not kept out.
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

    @Test
    void aConsoleWatchedForReadinessIsLetGoOnceReadyAndAStopCommandOpensItOnlyToStop() {
        FakeNativeDaemons.exclusiveConsoles = true;
        Db.run(datasource, () -> {
            // 1. A template declaring a readiness line and a stop command: deploy watches the console for the line.
            int instanceId = instanceRecord("exclusive-ready", template("exclusive-ready", "READY", "shutdown"));
            new InstanceService().deploy(instanceId);
            String handle = FakeNativeDaemons.handleOf(instanceId);
            FakeNativeDaemons.ScriptedStream watch = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(watch).as("step 1: deploy opened the console to watch for the line").isNotNull();
            assertThat(status(instanceId)).as("step 1: starting until the line is seen")
                .isEqualTo(InstanceModel.STATUS_STARTING);

            // 2. The line arrives: it runs, and the console is let go though the template declares a stop command,
            //    so the VM's screen and the operator's own console are not kept out.
            watch.push("booting\nREADY\n");
            Poll.until("step 2: ready, and the console let go", Duration.ofSeconds(10),
                () -> InstanceModel.STATUS_RUNNING.equals(status(instanceId)) && watch.isClosed());
            assertThat(InstanceConsoles.peek(instanceId)).as("step 2: the hub holds no session").isNull();

            // 3. A stop opens the console again for the stop command alone, and lets it go once it is sent.
            JobRunner.startVirtualThread(() -> {
                Poll.until("step 3: the stop command reached the workload", Duration.ofSeconds(10), () -> {
                    FakeNativeDaemons.ScriptedStream stopping = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
                    return stopping != watch && stopping != null && !stopping.stdinWrites().isEmpty();
                });
                FakeNativeDaemons.CONSOLE_STREAMS.get(handle).endFromDaemon();
            });
            new InstanceService().stop(instanceId);
            FakeNativeDaemons.ScriptedStream stopping = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(stopping).as("step 3: the stop opened a console of its own").isNotSameAs(watch);
            assertThat(stopping.stdinWrites()).as("step 3: carrying the stop command").containsExactly("shutdown\n");
            assertThat(stopping.isClosed()).as("step 3: and nothing kept it afterwards").isTrue();
            assertThat(InstanceConsoles.peek(instanceId)).as("step 3: the hub holds no session").isNull();
            assertThat(status(instanceId)).as("step 3: the workload stopped").isEqualTo(InstanceModel.STATUS_STOPPED);
        });
    }

    @Test
    void aConsoleAttachedAfterStartSeesWhatWasPrintedBeforeAndAttachesAgainWhenTheGuestReboots() {
        FakeNativeDaemons.exclusiveConsoles = true;
        FakeNativeDaemons.deferredConsoles = true;
        Db.run(datasource, () -> {
            // 1. The line was printed before the watch could attach (a VM's deploy waits for its guest agent): it is
            //    read from the backlog taken before the attach, since an attached VM console's log reads empty.
            int early = instanceRecord("deferred-early", template("deferred-early", "READY", null));
            FakeNativeDaemons.TAILS.put(FakeNativeDaemons.handleOf(early), "booting\nREADY\n");
            new InstanceService().deploy(early);
            Poll.until("step 1: the line printed before the attach counts", Duration.ofSeconds(10),
                () -> InstanceModel.STATUS_RUNNING.equals(status(early))
                    && FakeNativeDaemons.CONSOLE_STREAMS.get(FakeNativeDaemons.handleOf(early)).isClosed());

            // 2. The guest reboots before printing the line (an Alpine cloud VM resets after its first start): its
            //    console operation ends, and the watch attaches again while the line is still awaited.
            int rebooting = instanceRecord("deferred-reboot", template("deferred-reboot", "READY", null));
            new InstanceService().deploy(rebooting);
            String handle = FakeNativeDaemons.handleOf(rebooting);
            FakeNativeDaemons.ScriptedStream before = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(status(rebooting)).as("step 2: starting").isEqualTo(InstanceModel.STATUS_STARTING);
            before.endFromDaemon();
            Poll.until("step 2: the watch attached again", Duration.ofSeconds(10),
                () -> FakeNativeDaemons.CONSOLE_STREAMS.get(handle) != before);
            FakeNativeDaemons.ScriptedStream after = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            after.push("second boot\nREADY\n");
            Poll.until("step 2: the line on the second boot is seen, and the console let go", Duration.ofSeconds(10),
                () -> InstanceModel.STATUS_RUNNING.equals(status(rebooting)) && after.isClosed());
        });
    }

    private static String status(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS);
    }

    private static int template(String name, String readinessLine, String stopCommand) {
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.DESCRIPTION, "exclusive console fixture");
        row.set(InstanceTemplateModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceTemplateModel.VERSION, 1);
        row.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.CONSOLE_LINE.token());
        row.set(InstanceTemplateModel.READINESS_LINE, readinessLine);
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
