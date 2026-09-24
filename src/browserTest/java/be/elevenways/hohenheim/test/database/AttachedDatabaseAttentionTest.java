package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.cms.DatabaseAttention;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attached-database attention item judges STORED state, so rendering the dashboard asks
 * no daemon: the engine instance's stored status, the database record's status and the
 * engine host's recorded probe failure each name their own sentence, and a daemon transport
 * that counts every call made on the rendering thread stays at zero.
 *
 * AIDEV-NOTE: the count is per THREAD on purpose. The booted runtime runs real cron tasks
 * that may reach the local daemon transport at any moment; only a call made by the render
 * itself (the collector runs synchronously on the calling thread, and so did the per-link
 * DatabaseService.detailOf it replaced) is evidence. Step 1 proves the transport IS on the
 * live-status path, so the zeros after it are not vacuous.
 */
class AttachedDatabaseAttentionTest {

    private static final String PREFIX = "attdb-";

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshBootedDatasource();
    }

    /** A local-daemon transport that answers nothing and counts the calls of one thread. */
    private static final class CountingTransport implements DockerTransport {

        private final Thread owner = Thread.currentThread();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs) throws IOException {
            return this.roundTrip(request, timeoutMs, Long.MAX_VALUE);
        }

        @Override
        public byte[] roundTrip(byte[] request, long timeoutMs, long maxResponseBytes)
                throws IOException {
            if (Thread.currentThread() == this.owner) {
                this.calls.incrementAndGet();
            }
            throw new IOException("test transport: the dashboard must not ask a daemon");
        }
    }

    @Test
    void attachedDatabaseItemsReadStoredStateAndNeverAskTheDaemon() {
        CountingTransport transport = new CountingTransport();
        Db.run(datasource, () -> {
            int hostId = host(PREFIX + "host");
            int webId = consumer(PREFIX + "web", hostId);
            int databaseId = database(PREFIX + "db", hostId);
            int engineId = engineOf(databaseId, hostId);
            link(webId, databaseId);

            DockerClient.overrideLocalTransportForTest(() -> transport);
            try {
                // 1. The seam is ON the live path: asking the engine's live status goes
                //    through the local daemon transport, so every zero below means "not
                //    asked", never "asked somewhere this test cannot see".
                try {
                    new InstanceService().liveStatus(engineId);
                } catch (RuntimeException unreachable) {
                    // the transport refuses by design; only the call count matters here
                }
                assertThat(transport.calls.get())
                    .as("step 1: a live status query reaches the counting transport").isPositive();
                transport.calls.set(0);

                // 2. Stored RUNNING engine: the attachment serves, no item, no daemon call.
                assertThat(itemFor(PREFIX + "web"))
                    .as("step 2: a stored running engine raises nothing").isNull();

                // 3. Stored STOPPED engine: "not running", a warning linked to the
                //    consumer's Databases tab.
                engineStatus(engineId, InstanceModel.STATUS_STOPPED);
                AttentionItem stopped = itemFor(PREFIX + "web");
                assertThat(stopped).as("step 3: a stopped engine raises an item").isNotNull();
                assertThat(stopped.severity()).as("step 3: as a warning").isEqualTo(AttentionSeverity.WARNING);
                assertThat(stopped.detail().key()).as("step 3: naming it not running")
                    .isEqualTo("database_not_running");
                assertThat(stopped.target().toUrl()).as("step 3: linked to the consumer's Databases tab")
                    .isEqualTo("/admin/instances/" + webId + "/page/databases");

                // 4. Stored ERROR engine (crash detection stamped it): its own sentence.
                engineStatus(engineId, InstanceModel.STATUS_ERROR);
                assertThat(itemFor(PREFIX + "web").detail().key())
                    .as("step 4: a failed engine says so, not merely 'not running'")
                    .isEqualTo("database_failed");

                // 5. Stored RUNNING engine on a host whose last probe FAILED: the status is
                //    unverified, and the item says the host could not be reached.
                engineStatus(engineId, InstanceModel.STATUS_RUNNING);
                hostErrorKind(hostId, "unreachable");
                assertThat(itemFor(PREFIX + "web").detail().key())
                    .as("step 5: an unanswering host is its own problem, never 'stopped'")
                    .isEqualTo("database_unreachable");
                hostErrorKind(hostId, null);
                assertThat(itemFor(PREFIX + "web"))
                    .as("step 5: and a host answering again clears it").isNull();

                // 5b. Stored RUNNING engine whose sweep saw the engine OOM-killed inside the
                //     still-running container: the stored kill names its own sentence, read
                //     from the row with no daemon call, and clearing it clears the item.
                engineKilledAt(engineId, Now.instant());
                AttentionItem killed = itemFor(PREFIX + "web");
                assertThat(killed).as("step 5b: an OOM-killed engine raises an item").isNotNull();
                assertThat(killed.detail().key())
                    .as("step 5b: naming the kill, never 'running' and never 'not running'")
                    .isEqualTo("database_workload_dead");
                assertThat(transport.calls.get())
                    .as("step 5b: the stored kill surfaced without asking a daemon").isZero();
                engineKilledAt(engineId, null);
                assertThat(itemFor(PREFIX + "web"))
                    .as("step 5b: and a sweep that no longer sees the kill clears it").isNull();

                // 6. The RECORD is not active: the record's own status is the sentence.
                Model databases = Models.get(DatabaseModel.class);
                databases.find().where(DatabaseModel.ID.eq(databaseId))
                    .assign(DatabaseModel.STATUS, DatabaseModel.STATUS_PROVISIONING)
                    .bypassBehaviours()
                    .updateAll();
                AttentionItem provisioning = itemFor(PREFIX + "web");
                assertThat(provisioning.detail().key()).as("step 6: the record status speaks first")
                    .isEqualTo("database_status");
                assertThat(provisioning.detail().args().get("status"))
                    .as("step 6: naming the state").isEqualTo(DatabaseModel.STATUS_PROVISIONING);

                // 7. THE DEFECT: the whole dashboard collection, every role enabled, made no
                //    daemon call on the rendering thread -- this lane included.
                AttentionCollector.collect();
                assertThat(transport.calls.get())
                    .as("step 7: rendering the attention items asked no daemon").isZero();
            } finally {
                DockerClient.overrideLocalTransportForTest(null);
            }
        });
    }

    /** The item this collector raises for the named consumer instance, or null. */
    private static AttentionItem itemFor(String instanceName) {
        List<AttentionItem> items = new ArrayList<>();
        DatabaseAttention.unavailableAttachedDatabases(items);
        for (AttentionItem item : items) {
            if (instanceName.equals(String.valueOf(item.title().args().get("name")))) {
                return item;
            }
        }
        return null;
    }

    private static int host(String name) {
        Model servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        servers.save(row);
        // The same admitted, measured host InstanceDatabaseSurfaceTest places its pair on,
        // so the instance and database write gates accept the rows below.
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store(name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Now.instant(), null));
        return row.get(ServerModel.ID);
    }

    private static int consumer(String name, int serverId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int database(String name, int serverId) {
        Model databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cr3t-attention-pw");
        row.set(DatabaseModel.SERVER_ID, serverId);
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        databases.save(row);
        return row.get(DatabaseModel.ID);
    }

    /** Plant the engine instance with no daemon, then place it on the test host. */
    private static int engineOf(int databaseId, int serverId) {
        EngineHandles.plant(databaseId, PREFIX + "db", "postgres", InstanceModel.STATUS_RUNNING);
        Row engine = DatabaseInstances.owned(databaseId);
        assertThat(engine).as("the planted engine instance serves the database").isNotNull();
        int engineId = engine.get(InstanceModel.ID);
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(engineId))
            .assign(InstanceModel.SERVER_ID, serverId)
            .bypassBehaviours()
            .updateAll();
        return engineId;
    }

    private static void link(int instanceId, int databaseId) {
        Model links = Models.get(InstanceDatabaseModel.class);
        Row link = links.createEmptyRow();
        link.set(InstanceDatabaseModel.INSTANCE_ID, instanceId);
        link.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
        link.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
        links.save(link);
    }

    /** What the status reconciler would have stamped, written the way it writes (hook-free). */
    private static void engineStatus(int engineId, String status) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(engineId))
            .assign(InstanceModel.STATUS, status)
            .bypassBehaviours()
            .updateAll();
    }

    /** What the status reconciler stamps when it observes (or stops observing) an OOM kill. */
    private static void engineKilledAt(int engineId, Instant killedAt) {
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(engineId))
            .assign(InstanceModel.WORKLOAD_KILLED_AT, killedAt)
            .bypassBehaviours()
            .updateAll();
    }

    /** What a failed (or succeeding) host probe records. */
    private static void hostErrorKind(int serverId, String kind) {
        Models.get(ServerModel.class).find().where(ServerModel.ID.eq(serverId))
            .assign(ServerModel.LAST_ERROR_KIND, kind)
            .bypassBehaviours()
            .updateAll();
    }
}
