package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link DatabaseService}: create persists + provisions, backup resolves
 * the engine/credentials by name, destroy removes both container and record. Uses an isolated
 * SQLite + the live Docker daemon (skipped without either).
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class DatabaseServiceTest {

    private static PrivateNetns netns;

    @BeforeAll
    static void enforcePolicy() throws IOException {
        netns = PrivateNetns.installEnforcing();
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: record-backed provisioning refuses without an enforceable policy");
    }

    @AfterAll
    static void restorePolicy() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String PG_IMAGE = "postgres:17-alpine";

    @Test
    @SuppressWarnings("unchecked")
    void createPersistsProvisionsBackupByNameAndDestroyRemovesRecord() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(datasource);

        String name = "svc" + System.nanoTime();
        String[] containerName = new String[1];
        try {
            ManagedDatabase.Connection conn = service.create(name, ManagedDatabase.Engine.POSTGRES,
                PG_IMAGE, "appuser", "secret123", "appdb",
                true, ServerService.LOCAL, ResourceLimits.of(256, 1.0));   // ephemeral: tmpfs, no btrfs I/O
            assertThat(conn.port()).isGreaterThan(0);

            // create() persisted exactly one record with the right config.
            List<Row> all = service.list();
            assertThat(all).hasSize(1);
            assertThat((String) all.get(0).get(DatabaseModel.ENGINE)).isEqualTo("postgres");
            assertThat((Boolean) all.get(0).get(DatabaseModel.EPHEMERAL)).isTrue();
            assertThat((Integer) all.get(0).get(DatabaseModel.SERVER_ID))
                .isEqualTo(ServerModel.localServerId());   // default host
            assertThat((Integer) all.get(0).get(DatabaseModel.MEMORY_LIMIT_MB)).isEqualTo(256);
            assertThat((Double) all.get(0).get(DatabaseModel.CPU_LIMIT)).isEqualTo(1.0);

            containerName[0] = Db.supply(datasource, () -> EngineHandles.of(name));

            // The caps reach the container's HostConfig.
            Map<String, Object> hostConfig =
                (Map<String, Object>) docker.inspectContainer(containerName[0]).get("HostConfig");
            assertThat(((Number) hostConfig.get("Memory")).longValue()).isEqualTo(256L * 1024 * 1024);
            assertThat(((Number) hostConfig.get("NanoCpus")).longValue()).isEqualTo(1_000_000_000L);

            // backupDownload(name) resolves engine + credentials from the record (caller passes no params).
            DockerClient.ExecResult seed = docker.exec(containerName[0],
                List.of("psql", "-U", "appuser", "-d", "appdb", "-c", "CREATE TABLE t (id int);"),
                List.of("PGPASSWORD=secret123"));
            assertThat(seed.exitCode()).withFailMessage("seed failed: %s", seed.stderr()).isZero();
            DatabaseService.BackupDownload download = service.backupDownload(name);
            assertThat(new String(download.content(), StandardCharsets.UTF_8))
                .contains("CREATE TABLE");

            service.destroy(name, true);
            assertThat(service.list()).isEmpty();              // record gone
            try {
                docker.inspectContainer(containerName[0]);
                throw new AssertionError("expected container to be removed");
            } catch (IOException expected) {
                // 404 from the daemon -> container removed, as intended
            }
        } finally {
            // ensure no leftovers if an assertion above failed before destroy ran
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    @Test
    void createAsyncRecordsProvisioningThenActive() throws IOException, InterruptedException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = "async" + System.nanoTime();
        try {
            service.createAsync(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);

            // The call returned without blocking on the pull: the record is already "provisioning".
            assertThat(service.detail(name).status()).isEqualTo(DatabaseService.STATUS_PROVISIONING);

            // The background job flips it to "active" once the container is up and ready.
            long deadline = System.currentTimeMillis() + 60_000;
            String status = service.detail(name).status();
            while (!DatabaseService.STATUS_ACTIVE.equals(status) && System.currentTimeMillis() < deadline) {
                Thread.sleep(500);
                status = service.detail(name).status();
            }
            assertThat(status).isEqualTo(DatabaseService.STATUS_ACTIVE);
            assertThat(service.detail(name).running()).isTrue();
        } finally {
            service.destroy(name, true);
        }
    }

    /**
     * Record-after against the real daemon: the port the kernel handed the container is
     * in the ledger, attributed to the database record, and released when it is destroyed.
     * The live half of PortLedgerTest.recordAfterLearnsRelearnsReportsAndReleases -- that
     * one proves the logic without a daemon, this one proves the wiring reaches it.
     */
    @Test
    void theProvisionedPortIsClaimedInTheLedgerAndFreedOnDestroy() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(datasource);
        String name = "ledger" + System.nanoTime();
        try {
            ManagedDatabase.Connection conn = service.create(name, ManagedDatabase.Engine.POSTGRES,
                PG_IMAGE, "appuser", "secret123", "appdb", true, ServerService.LOCAL);

            Db.run(datasource, () -> {
                Integer recordId = service.list().get(0).get(DatabaseModel.ID);
                Integer instanceId = DatabaseInstances.owned(recordId).get(InstanceModel.ID);
                String key = PortLedger.claimKeyOf(ServerModel.localServerId(),
                    conn.host(), conn.port(), "tcp");
                // 1. The published port is a ledger claim owned by the engine INSTANCE
                //    (the record-after write InstanceService.deploy does for every kind).
                Row claim = PortLedger.holderOf(key);
                assertThat(claim).as("step 1: the published port is in the ledger").isNotNull();
                assertThat(PortLedger.isOwnedBy(claim, InstanceModel.MODEL_ID, instanceId))
                    .as("step 1: the claim names the engine instance as owner").isTrue();
                // 2. It is therefore visible to EVERY other authority: a stack declaring
                //    the same host port now collides instead of silently double-booking.
                assertThatThrownBy(() -> PortLedger.claim(ServerModel.localServerId(), "0.0.0.0",
                        conn.port(), "tcp", null, null, "a rival stack service"))
                    .as("step 2: another authority is refused the same port")
                    .isInstanceOf(PortLedger.PortConflict.class);
            });

            service.destroy(name, true);
            Db.run(datasource, () -> assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(
                    ServerModel.localServerId(), conn.host(), conn.port(), "tcp")))
                .as("step 3: destroying the database released its port").isNull());
        } finally {
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * The C6 destroy contract, end to end on a real daemon: a destroy whose HOST cannot
     * be addressed REFUSES (record + credentials kept, status destroy_failed, container
     * STILL RUNNING on the host, port claim still blocking rivals), a verified destroy
     * then cleans everything, and absent vs unreachable are distinct container states.
     *
     * AIDEV-NOTE: the unreachable daemon is now spelled as an unaddressable HOST RECORD
     * rather than an injected broken client, because the lowering removed the injected
     * client: the engine's runtime is resolved by the KIND through the host inventory, so
     * a second client-resolution path in the test would exercise a lane production does
     * not have. Moving the owned instance to an unpinned SSH host is the honest
     * equivalent -- nothing can build a daemon connection for it.
     */
    @Test
    void destroyRefusesToLieWhenTheHostCannotBeAddressed() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(datasource);

        String name = "c6destroy" + System.nanoTime();
        String[] handle = new String[1];
        try {
            // 1. Provision for real; the OWNED INSTANCE holds the ledger claim now.
            ManagedDatabase.Connection conn = service.create(name, ManagedDatabase.Engine.POSTGRES,
                PG_IMAGE, "appuser", "secret123", "appdb", true, ServerService.LOCAL);
            int[] instanceId = new int[1];
            Db.run(datasource, () -> {
                Integer recordId = service.list().get(0).get(DatabaseModel.ID);
                Row instance = DatabaseInstances.owned(recordId);
                assertThat(instance).as("step 1: the database owns an engine instance").isNotNull();
                instanceId[0] = instance.get(InstanceModel.ID);
                handle[0] = EngineHandles.of(name);
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId[0]))
                    .as("step 1: the provisioned port is claimed by the INSTANCE").hasSize(1);
                assertThat(PortLedger.claimsOf(DatabaseModel.MODEL_ID, recordId))
                    .as("step 1: and no longer by the database record").isEmpty();
            });

            // 2. Move the engine onto a host nothing can build a daemon connection for.
            Db.run(datasource, () -> {
                Row phantom = Models.get(ServerModel.class).createEmptyRow();
                phantom.set(ServerModel.NAME, "c6-phantom-" + System.nanoTime());
                phantom.set(ServerModel.MODE, ServerModel.MODE_SSH);
                phantom.set(ServerModel.SSH_TARGET, "nobody@phantom.invalid");
                Models.get(ServerModel.class).save(phantom);
                // Through the OWNING tier's system scope: the generated-only guard
                // refuses any other write to this kind, fixtures included.
                moveEngineTo(instanceId[0], phantom.get(ServerModel.ID));
            });

            // 3. A destroy through it REFUSES instead of lying.
            assertThatThrownBy(() -> service.destroy(name, true))
                .as("step 3: destroy through an unaddressable host must throw")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("could not verify its teardown");

            // 4. Nothing was deleted and nothing was freed optimistically: record kept
            //    with status destroy_failed, password still readable, container STILL
            //    RUNNING on the host, claim still blocking rivals.
            Db.run(datasource, () -> {
                Row kept = service.list().isEmpty() ? null : service.list().get(0);
                assertThat(kept).as("step 4: the record survives the failed destroy").isNotNull();
                assertThat((String) kept.get(DatabaseModel.STATUS))
                    .as("step 4: the failure has its own named status")
                    .isEqualTo(DatabaseModel.STATUS_DESTROY_FAILED);
                assertThat((String) kept.get(DatabaseModel.DB_PASSWORD))
                    .as("step 4: the only copy of the password is kept")
                    .isEqualTo("secret123");
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId[0]))
                    .as("step 4: the port claim survives").hasSize(1);
                assertThatThrownBy(() -> PortLedger.claim(ServerModel.localServerId(), "0.0.0.0",
                        conn.port(), "tcp", null, null, "a rival stack service"))
                    .as("step 4: and still refuses a rival the same port")
                    .isInstanceOf(PortLedger.PortConflict.class);
            });
            Object state = docker.inspectContainer(handle[0]).get("State");
            assertThat(state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running")))
                .as("step 4: HOST state -- the container is genuinely still running").isTrue();

            // 5. Point the engine back at the reachable host: the VERIFIED destroy then
            //    succeeds completely -- record gone, container gone, ledger empty.
            Db.run(datasource, () -> {
                moveEngineTo(instanceId[0], ServerModel.localServerId());
            });
            Db.run(datasource, () -> {
                try {
                    service.destroy(name, true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                assertThat(service.list()).as("step 5: the record is gone").isEmpty();
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId[0]))
                    .as("step 5: the verified destroy freed the claim entirely").isEmpty();
            });
            try {
                docker.inspectContainer(handle[0]);
                throw new AssertionError("step 5: expected the container to be gone");
            } catch (DockerClient.ApiException e) {
                assertThat(e.isNotFound())
                    .as("step 5: HOST state -- the daemon reports the container absent").isTrue();
            }
        } finally {
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
            if (handle[0] != null) {
                try {
                    docker.removeContainer(handle[0], true);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** Re-home an owned engine instance through the database tier's system scope. */
    private static void moveEngineTo(int instanceId, Integer serverId) {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        Integer databaseId = instance.get(InstanceModel.GENERATED_FOR_ID);
        be.elevenways.hohenheim.server.instance.OwnedInstances.inScopeUnchecked(
            DatabaseInstances.SOURCE, DatabaseModel.MODEL_ID, databaseId, () -> {
                Row row = Models.get(InstanceModel.class).findById(instanceId);
                row.set(InstanceModel.SERVER_ID, serverId);
                Models.get(InstanceModel.class).save(row);
            });
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-dbservice-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(ds).migrate().requireSuccess();
        // The container NAME is derived from the controller identity in THIS database, so
        // it must be the one every unscoped call resolves to as well.
        Datasources.register(Datasources.DEFAULT, ds);
        HohenheimTestRuntime.ensureBooted();
        return ds;
    }
}
