package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceLogModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.ConsoleRedaction;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.hohenheim.server.task.CleanOldInstanceLogs;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live stats and console logs as a DAEMON-FREE journey: the hub, the sample decode, the
 * bounded ring, the shared stream, ingest-time redaction, the upserted history row and the
 * retention sweep, all against the scripted driver streams of {@link FakeNativeDaemons}.
 *
 * This exists because the stats lane had exactly ONE test and it assumed three times (a
 * Docker socket, a pre-pulled alpine, a private netns), so every regression in the decode
 * was invisible on any machine missing one of them. Scripting the driver's frames also buys
 * assertions the live test structurally CANNOT make: exact CPU arithmetic from known
 * counters, a sample split across a chunk boundary, an unparseable line, and the bound of
 * the ring reached on purpose.
 *
 * WHAT THIS CANNOT PROVE, and therefore what {@code InstanceStatsLiveTest} remains the only
 * proof of: that a real daemon's {@code /stats} stream carries the keys this decode reads,
 * in the NDJSON framing it assumes, and that a container really burning a core reports
 * non-zero CPU. A fake cannot discover an Engine API change; it can only prove that what we
 * do with the bytes is right.
 */
class InstanceObservabilityContractTest {

    private static SqlDatasource datasource;
    private static int hostId;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        // ONE registered database per class: the controller identity every daemon
        // resource name resolves through reads the CURRENT datasource.
        HohenheimTestRuntime.ensureBooted();
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = incusHost("observability-host"));
    }

    @AfterEach
    void closeStreams() {
        InstanceStats.shutdown();
        FakeNativeDaemons.resetStreams();
    }

    @AfterAll
    static void tearDown() {
        InstanceStats.shutdown();
    }

    /**
     * The stats hub end to end: decode, chunk boundaries, garbage tolerance, the bounded
     * ring, one shared stream for many viewers, and the teardown when the last one leaves.
     */
    @Test
    void liveStatsDecodeSharesOneStreamAndTearsItDown() {
        Db.run(datasource, () -> {
            int instanceId = instanceRecord("stats-hermetic", FakeNativeDaemons.FakeNativeKind.ID);
            new InstanceService().deploy(instanceId);
            String handle = FakeNativeDaemons.handleOf(instanceId);

            List<InstanceStats.Sample> first = new CopyOnWriteArrayList<>();
            InstanceStats.Subscription firstView;
            try {
                firstView = InstanceStats.subscribe(instanceId, first::add);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.STATS_STREAMS.get(handle);
            assertThat(stream)
                .as("the hub opened the driver's stats lane").isNotNull();

            // 1. The FIRST sample has no previous observation to delta against, so it
            //    honestly reports no CPU -- reporting the lifetime average here is the
            //    exact lie the decode's own note forbids.
            stream.push(sample(1_000_000_000L, 10_000_000_000L, 300, 400) + "\n");
            await("step 1: the first sample arrives", () -> first.size() >= 1);
            assertThat(first.get(0).cpuPercent())
                .as("step 1: the first sample reports 0% CPU, not a lifetime average")
                .isEqualTo(0d);
            assertThat(first.get(0).memoryBytes())
                .as("step 1: memory comes straight off the sample")
                .isEqualTo(536_870_912L);
            assertThat(first.get(0).memoryLimit())
                .as("step 1: and so does the cgroup limit").isEqualTo(1_073_741_824L);
            assertThat(first.get(0).rxBytes())
                .as("step 1: rx is SUMMED across every interface").isEqualTo(300L + 7L);
            assertThat(first.get(0).txBytes())
                .as("step 1: and so is tx").isEqualTo(400L + 9L);

            // 2. THE ARITHMETIC, which a live container cannot be made to produce on
            //    demand: 0.1s of CPU over 0.4s of system time on 4 cores is 100%.
            stream.push(sample(1_100_000_000L, 10_400_000_000L, 500, 600) + "\n");
            await("step 2: the second sample arrives", () -> first.size() >= 2);
            assertThat(first.get(1).cpuPercent())
                .as("step 2: cpu is usageDelta/systemDelta * cores * 100, exactly")
                .isEqualTo(100.0d);

            // 3. A sample SPLIT across two frames is one sample, not two broken ones --
            //    the daemon's chunk boundaries land wherever they land.
            String third = sample(1_200_000_000L, 10_800_000_000L, 700, 800) + "\n";
            int half = third.length() / 2;
            stream.push(third.substring(0, half));
            assertThat(first.size())
                .as("step 3: half a sample decodes to nothing at all").isEqualTo(2);
            stream.push(third.substring(half));
            await("step 3: the reassembled sample arrives", () -> first.size() >= 3);
            assertThat(first.get(2).cpuPercent())
                .as("step 3: and it decodes to the SAME reading a whole frame would")
                .isEqualTo(100.0d);

            // 4. An unparseable line is skipped and the stream SURVIVES it: one bad frame
            //    must not end an operator's live chart.
            stream.push("this is not a stats object\n");
            stream.push(sample(1_300_000_000L, 11_200_000_000L, 900, 1000) + "\n");
            await("step 4: the stream survived the garbage", () -> first.size() >= 4);
            assertThat(first.size())
                .as("step 4: the garbage line produced no sample of its own").isEqualTo(4);

            // 5. A SECOND viewer shares the one stream and is replayed the ring, so it has
            //    a chart immediately instead of a blank minute.
            List<InstanceStats.Sample> second = new CopyOnWriteArrayList<>();
            InstanceStats.Subscription secondView;
            try {
                secondView = InstanceStats.subscribe(instanceId, second::add);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            assertThat(second)
                .as("step 5: a joining viewer is replayed the retained ring")
                .hasSize(4);
            assertThat(FakeNativeDaemons.STATS_STREAMS.get(handle))
                .as("step 5: and NO second driver stream was opened for it")
                .isSameAs(stream);
            stream.push(sample(1_400_000_000L, 11_600_000_000L, 1100, 1200) + "\n");
            await("step 5: both viewers keep receiving from the one stream",
                () -> first.size() >= 5 && second.size() >= 5);

            // 6. The ring is BOUNDED: history never exceeds the declared window however
            //    long the stream runs.
            long usage = 2_000_000_000L;
            long system = 20_000_000_000L;
            for (int i = 0; i < InstanceStats.HISTORY + 10; i++) {
                usage += 100_000_000L;
                system += 400_000_000L;
                stream.push(sample(usage, system, 1, 1) + "\n");
            }
            await("step 6: every pushed sample was consumed",
                () -> InstanceStats.history(instanceId).size() == InstanceStats.HISTORY);
            assertThat(InstanceStats.history(instanceId))
                .as("step 6: retained history stays inside the declared window")
                .hasSize(InstanceStats.HISTORY);

            // 7. The LAST viewer leaving tears the DRIVER stream down: an orphaned
            //    per-second stream per instance is a real socket and a real cost.
            firstView.close();
            assertThat(stream.isClosed())
                .as("step 7: one viewer leaving keeps the shared stream alive").isFalse();
            secondView.close();
            await("step 7: the last viewer leaving closes the driver stream",
                stream::isClosed);
            assertThat(InstanceStats.history(instanceId))
                .as("step 7: and the session is dropped entirely").isEmpty();
        });
    }

    /**
     * The refusals: a driver with no stats lane and a stats read that does not answer are
     * both NAMED failures, never an empty chart that looks like an idle workload.
     */
    @Test
    void aWorkloadWithoutStatsIsRefusedByNameRatherThanCharted() {
        Db.run(datasource, () -> {
            // 1. A driver WITHOUT the lane (FakeVolumeKind exposes no StatsStreamSupport).
            int laneless = instanceRecord("stats-laneless",
                FakeNativeDaemons.FakeVolumeKind.ID);
            new InstanceService().deploy(laneless);
            assertThatThrownBy(() -> InstanceStats.subscribe(laneless, sample -> { }))
                .as("step 1: a driver without a stats lane is refused BY NAME")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no live-stats lane");
            assertThat(InstanceStats.history(laneless))
                .as("step 1: and no empty chart is retained for it").isEmpty();

            // 2. A driver WITH the lane whose stats read does not answer: the failure is
            //    the caller's, not a silently blank chart.
            int failing = instanceRecord("stats-failing",
                FakeNativeDaemons.FakeNativeKind.ID);
            new InstanceService().deploy(failing);
            FakeNativeDaemons.STATS_FAILS.add(FakeNativeDaemons.handleOf(failing));
            assertThatThrownBy(() -> InstanceStats.subscribe(failing, sample -> { }))
                .as("step 2: a stats read that does not answer surfaces as the IO failure")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not answer");
            assertThat(InstanceStats.history(failing))
                .as("step 2: and leaves no session behind").isEmpty();

            // 3. A STOPPED workload has no stats either, and says so.
            int stopped = instanceRecord("stats-stopped",
                FakeNativeDaemons.FakeNativeKind.ID);
            new InstanceService().deploy(stopped);
            new InstanceService().stop(stopped);
            assertThatThrownBy(() -> InstanceStats.subscribe(stopped, sample -> { }))
                .as("step 3: a stopped workload is refused, never charted at zero")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not running");
        });
    }

    /**
     * The console's durable half: secrets are redacted AT INGEST so nothing stores them,
     * one episode is ONE upserted row however often it flushes, and the retention sweep
     * bounds the row set by age.
     */
    @Test
    void consoleOutputIsRedactedAtIngestPersistedOnceAndSwept() {
        Db.run(datasource, () -> {
            int instanceId = instanceRecord("console-hermetic",
                FakeNativeDaemons.FakeNativeKind.ID);
            String secret = "s3cret-database-password";
            secretVariable(instanceId, "DB_PASSWORD", secret);
            new InstanceService().deploy(instanceId);
            String handle = FakeNativeDaemons.handleOf(instanceId);

            List<String> seen = new CopyOnWriteArrayList<>();
            InstanceConsoles.Subscription viewer = InstanceConsoles.subscribe(instanceId,
                seen::add);
            FakeNativeDaemons.ScriptedStream stream =
                FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(stream).as("the hub attached to the driver's console").isNotNull();

            // 1. Redaction is BY KNOWN VALUE and happens before any viewer sees the text.
            stream.push("connecting with " + secret + " now\n");
            await("step 1: the console output reached the viewer", () -> !seen.isEmpty());
            assertThat(String.join("", seen))
                .as("step 1: the declared secret never reaches a viewer")
                .doesNotContain(secret)
                .contains(ConsoleRedaction.PLACEHOLDER);

            // 2. The STORED row is the redacted ring, not the raw stream: storing raw text
            //    and redacting on read would leave a secret at rest.
            InstanceConsoles.flushLogNow(instanceId);
            await("step 2: the episode's history row exists", () -> logsOf(instanceId).size() == 1);
            Row stored = logsOf(instanceId).get(0);
            assertThat((String) stored.get(InstanceLogModel.LOG_TEXT))
                .as("step 2: nothing secret is ever written to instance_logs")
                .doesNotContain(secret)
                .contains(ConsoleRedaction.PLACEHOLDER);
            assertThat((String) stored.get(InstanceLogModel.HANDLE))
                .as("step 2: the row names the workload it mirrors").isEqualTo(handle);

            // 3. One EPISODE is one row: a second flush upserts, never appends a row.
            stream.push("second line of the same episode\n");
            await("step 3: the second line arrived",
                () -> String.join("", seen).contains("second line"));
            InstanceConsoles.flushLogNow(instanceId);
            await("step 3: the row was rewritten", () -> {
                Row row = logsOf(instanceId).get(0);
                String text = row.get(InstanceLogModel.LOG_TEXT);
                return text != null && text.contains("second line");
            });
            assertThat(logsOf(instanceId))
                .as("step 3: an episode keeps ONE row however often it flushes")
                .hasSize(1);
            assertThat((Integer) logsOf(instanceId).get(0).get(InstanceLogModel.LINE_COUNT))
                .as("step 3: whose line count is the ring's, not the flush's")
                .isGreaterThanOrEqualTo(2);

            // 4. A value declared secret WHILE the console streams is redacted from there
            //    on -- without this it would ride the open session in the clear.
            String late = "late-rotated-token-value";
            InstanceConsoles.registerSecret(instanceId, late);
            stream.push("rotated to " + late + " ok\n");
            await("step 4: the later line arrived",
                () -> String.join("", seen).contains("rotated to"));
            assertThat(String.join("", seen))
                .as("step 4: a secret declared mid-stream is redacted from there on")
                .doesNotContain(late);

            viewer.close();
            InstanceConsoles.flushLogNow(instanceId);

            // 5. RETENTION on the row set: a row whose LAST WRITE is past the window is
            //    swept, a fresh one is kept. Bounded on both axes -- the ring caps each
            //    row, this caps the set.
            Row aged = logsOf(instanceId).get(0);
            int agedId = aged.get(InstanceLogModel.ID);
            Models.get(InstanceLogModel.class).find()
                .where(InstanceLogModel.ID.eq(agedId))
                .assign(InstanceLogModel.SAVED_AT,
                    Instant.now().minus(31, ChronoUnit.DAYS))
                .updateAll();
            int freshId = logRow(instanceId, handle, "a fresh episode", Instant.now());
            // 5b. THE ROW THIS SWEEP MUST NOT TOUCH: a LIVE episode, opened long ago and
            //     written to a second ago. Sweeping by created_at deleted exactly this row
            //     and the next flush silently started a new one, losing the history the
            //     window was still supposed to hold. See CleanOldInstanceLogs.clean.
            int liveId = logRow(instanceId, handle, "a live episode", Instant.now());
            Models.get(InstanceLogModel.class).find()
                .where(InstanceLogModel.ID.eq(liveId))
                .assign(InstanceLogModel.CREATED_AT,
                    Instant.now().minus(60, ChronoUnit.DAYS))
                .updateAll();
            CleanOldInstanceLogs.clean();
            assertThat(Models.get(InstanceLogModel.class).findById(agedId))
                .as("step 5: a log row whose last write is past the window is swept")
                .isNull();
            assertThat(Models.get(InstanceLogModel.class).findById(freshId))
                .as("step 5: and a fresh one is kept").isNotNull();
            assertThat(Models.get(InstanceLogModel.class).findById(liveId))
                .as("step 5b: a month-old episode still being written to SURVIVES --"
                    + " its text is a second old, whatever its row's age is")
                .isNotNull();
        });
    }

    /**
     * The console refusals: a driver without a console lane and a console the daemon
     * cannot be asked about are both NAMED, never an empty log that reads as "no output".
     */
    @Test
    void anUnreadableConsoleIsRefusedByNameRatherThanReportedEmpty() {
        Db.run(datasource, () -> {
            // 1. A driver without the lane at all.
            int laneless = instanceRecord("console-laneless",
                FakeNativeDaemons.FakeVolumeKind.ID);
            new InstanceService().deploy(laneless);
            assertThatThrownBy(() -> InstanceConsoles.tail(laneless, 10))
                .as("step 1: a driver without a console lane is refused by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("console_unsupported");

            // 2. A driver WITH the lane whose tail read fails: logs_unavailable, not "".
            int unreadable = instanceRecord("console-unreadable",
                FakeNativeDaemons.FakeNativeKind.ID);
            new InstanceService().deploy(unreadable);
            FakeNativeDaemons.TAIL_FAILS.add(FakeNativeDaemons.handleOf(unreadable));
            assertThatThrownBy(() -> InstanceConsoles.tail(unreadable, 10))
                .as("step 2: an unreadable console is a named refusal, never empty output")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("logs_unavailable");

            // 3. And the readable one-shot tail is redacted like the stream is: the
            //    automation API must never be the wider door.
            int readable = instanceRecord("console-readable",
                FakeNativeDaemons.FakeNativeKind.ID);
            String secret = "one-shot-secret-value";
            secretVariable(readable, "TOKEN", secret);
            new InstanceService().deploy(readable);
            FakeNativeDaemons.TAILS.put(FakeNativeDaemons.handleOf(readable),
                "started with " + secret + "\n");
            assertThat(InstanceConsoles.tail(readable, 10))
                .as("step 3: the one-shot read carries its own redaction")
                .doesNotContain(secret)
                .contains(ConsoleRedaction.PLACEHOLDER);
        });
    }

    // -- fixture plumbing -----------------------------------------------------

    /** One docker-shaped stats object; every number the decode reads is declared here. */
    private static String sample(long cpuUsage, long systemUsage, long rx, long tx) {
        return "{\"cpu_stats\":{\"cpu_usage\":{\"total_usage\":" + cpuUsage + "},"
            + "\"system_cpu_usage\":" + systemUsage + ",\"online_cpus\":4},"
            + "\"memory_stats\":{\"usage\":536870912,\"limit\":1073741824},"
            + "\"networks\":{\"eth0\":{\"rx_bytes\":" + rx + ",\"tx_bytes\":" + tx + "},"
            + "\"eth1\":{\"rx_bytes\":7,\"tx_bytes\":9}}}";
    }

    /** An admitted, tenant-accepting host with the stored preflight placement demands. */
    private static int incusHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostPreflight.store(name, new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                HostPreflight.STATUS_PASS, true, "fake kernel-truth lane")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return Models.get(ServerModel.class).findByName(name).get(ServerModel.ID);
    }

    private static int instanceRecord(String name,
                                      be.elevenways.protoblast.common.registry.Identifier kind) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void secretVariable(int instanceId, String key, String value) {
        Row row = Models.get(InstanceVariableModel.class).createEmptyRow();
        row.set(InstanceVariableModel.INSTANCE_ID, instanceId);
        row.set(InstanceVariableModel.KEY, key);
        row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_SECRET);
        row.set(InstanceVariableModel.SECRET_VALUE, value);
        Models.get(InstanceVariableModel.class).save(row);
    }

    private static int logRow(int instanceId, String handle, String text, Instant savedAt) {
        Row row = Models.get(InstanceLogModel.class).createEmptyRow();
        row.set(InstanceLogModel.INSTANCE_ID, instanceId);
        row.set(InstanceLogModel.HANDLE, handle);
        row.set(InstanceLogModel.LOG_TEXT, text);
        row.set(InstanceLogModel.LINE_COUNT, 1);
        row.set(InstanceLogModel.SAVED_AT, savedAt);
        Models.get(InstanceLogModel.class).save(row);
        return row.get(InstanceLogModel.ID);
    }

    private static List<Row> logsOf(int instanceId) {
        return Models.get(InstanceLogModel.class).find()
            .where(InstanceLogModel.INSTANCE_ID.eq(instanceId))
            .all();
    }

    /** Bounded wait: every stream here is pumped by a thread of its own. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
