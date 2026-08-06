package be.elevenways.hohenheim.test.database;

import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.runtime.NetworkPosture;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.docker.UnixSocketDockerTransport;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link DatabaseService}: create persists + provisions, backup resolves
 * the engine/credentials by name, destroy removes both container and record. Uses an isolated
 * SQLite + the live Docker daemon (skipped without either).
 */
class DatabaseServiceTest {

    private static PrivateNetns netns;

    @BeforeAll
    static void enforcePolicy() throws IOException {
        netns = PrivateNetns.installEnforcing();
        assumeTrue(netns != null,
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
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(docker, datasource);

        String name = "svc" + System.nanoTime();
        String containerName = ControllerScope.handle(ControllerScope.KIND_DB, name);
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

            // The caps reach the container's HostConfig.
            Map<String, Object> hostConfig =
                (Map<String, Object>) docker.inspectContainer(containerName).get("HostConfig");
            assertThat(((Number) hostConfig.get("Memory")).longValue()).isEqualTo(256L * 1024 * 1024);
            assertThat(((Number) hostConfig.get("NanoCpus")).longValue()).isEqualTo(1_000_000_000L);

            // backupDownload(name) resolves engine + credentials from the record (caller passes no params).
            DockerClient.ExecResult seed = docker.exec(containerName,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-c", "CREATE TABLE t (id int);"),
                List.of("PGPASSWORD=secret123"));
            assertThat(seed.exitCode()).withFailMessage("seed failed: %s", seed.stderr()).isZero();
            DatabaseService.BackupDownload download = service.backupDownload(name);
            assertThat(new String(download.content(), StandardCharsets.UTF_8))
                .contains("CREATE TABLE");

            service.destroy(name, true);
            assertThat(service.list()).isEmpty();              // record gone
            try {
                docker.inspectContainer(containerName);
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
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");

        DatabaseService service = new DatabaseService(docker, freshDatasource());
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
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(docker, datasource);
        String name = "ledger" + System.nanoTime();
        try {
            ManagedDatabase.Connection conn = service.create(name, ManagedDatabase.Engine.POSTGRES,
                PG_IMAGE, "appuser", "secret123", "appdb", true, ServerService.LOCAL);

            Db.run(datasource, () -> {
                Integer recordId = service.list().get(0).get(DatabaseModel.ID);
                String key = PortLedger.claimKeyOf(ServerModel.localServerId(),
                    conn.host(), conn.port(), "tcp");
                // 1. The published port is a ledger claim owned by the database record.
                Row claim = PortLedger.holderOf(key);
                assertThat(claim).as("step 1: the published port is in the ledger").isNotNull();
                assertThat(PortLedger.isOwnedBy(claim, DatabaseModel.MODEL_ID, recordId))
                    .as("step 1: the claim names the database record as owner").isTrue();
                // 2. It is therefore visible to EVERY other authority: a stack declaring
                //    the same host port now collides instead of silently double-booking.
                assertThatThrownBy(() -> PortLedger.claim(ServerModel.localServerId(), "0.0.0.0",
                        conn.port(), "tcp", null, null, "a rival stack service"))
                    .as("step 2: another authority is refused the same port")
                    .isInstanceOf(PortLedger.PortConflict.class)
                    .hasMessageContaining(name);
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
     * The C6 destroy contract, end to end on a real daemon: a destroy that cannot reach
     * the daemon REFUSES (record + credentials kept, status destroy_failed, port claim
     * parked releasing, container STILL RUNNING on the host), a verified destroy then
     * cleans everything, and absent vs unreachable are distinct container states.
     */
    @Test
    void destroyRefusesToLieWhenTheDaemonIsUnreachable() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(docker, datasource);
        DockerClient unreachable = new DockerClient(
            new UnixSocketDockerTransport("/nonexistent/hohenheim-test.sock"));
        DatabaseService broken = new DatabaseService(unreachable, datasource);

        String name = "c6destroy" + System.nanoTime();
        String containerName = ControllerScope.handle(ControllerScope.KIND_DB, name);
        try {
            // 1. Provision for real; the record owns a ledger claim.
            ManagedDatabase.Connection conn = service.create(name, ManagedDatabase.Engine.POSTGRES,
                PG_IMAGE, "appuser", "secret123", "appdb", true, ServerService.LOCAL);
            Db.run(datasource, () -> {
                Integer recordId = service.list().get(0).get(DatabaseModel.ID);
                assertThat(PortLedger.claimsOf(DatabaseModel.MODEL_ID, recordId))
                    .as("step 1: the provisioned port is claimed").hasSize(1);
            });

            // 2. A destroy through an unreachable daemon REFUSES instead of lying.
            try {
                broken.destroy(name, true);
                throw new AssertionError("step 2: destroy through an unreachable daemon"
                    + " must throw, not report success");
            } catch (IOException expected) {
                assertThat(expected.getMessage())
                    .as("step 2: the refusal names the unverified teardown")
                    .contains("could not verify its teardown");
            }

            // 3. Nothing was deleted and nothing was freed optimistically: record kept
            //    with status destroy_failed, password still readable, container STILL
            //    RUNNING on the host, claim parked in releasing (blocking rivals).
            Db.run(datasource, () -> {
                Row kept = service.list().isEmpty() ? null : service.list().get(0);
                assertThat(kept).as("step 3: the record survives the failed destroy").isNotNull();
                assertThat((String) kept.get(DatabaseModel.STATUS))
                    .as("step 3: the failure has its own named status")
                    .isEqualTo(DatabaseModel.STATUS_DESTROY_FAILED);
                assertThat((String) kept.get(DatabaseModel.DB_PASSWORD))
                    .as("step 3: the only copy of the password is kept")
                    .isEqualTo("secret123");
                List<Row> claims = PortLedger.claimsOf(DatabaseModel.MODEL_ID,
                    kept.get(DatabaseModel.ID));
                assertThat(claims).as("step 3: the port claim survives").hasSize(1);
                assertThat(PortLedger.isReleasing(claims.get(0)))
                    .as("step 3: the surviving claim is parked in releasing").isTrue();
            });
            Object state = docker.inspectContainer(containerName).get("State");
            assertThat(state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running")))
                .as("step 3: HOST state -- the container is genuinely still running").isTrue();

            // 4. Absent vs unreachable are DISTINCT identities.
            assertThat(new ManagedDatabase(unreachable, WorkloadNetworkPolicy.forServer(ServerModel.MODE_LOCAL), NetworkPosture.SHARED_BRIDGE)
                    .status(name, ManagedDatabase.Engine.POSTGRES).state())
                .as("step 4: an unaskable daemon is UNREACHABLE, not 'gone'")
                .isEqualTo(ContainerState.UNREACHABLE);
            assertThat(new ManagedDatabase(docker, WorkloadNetworkPolicy.forServer(ServerModel.MODE_LOCAL), NetworkPosture.SHARED_BRIDGE)
                    .status(name, ManagedDatabase.Engine.POSTGRES).state())
                .as("step 4: the reachable daemon reports the container RUNNING")
                .isEqualTo(ContainerState.RUNNING);

            // 5. The VERIFIED destroy then succeeds completely: record gone, container
            //    gone on the host, ledger empty, and the name reads ABSENT (not
            //    unreachable) through the live daemon.
            Db.run(datasource, () -> {
                Integer recordId = service.list().get(0).get(DatabaseModel.ID);
                try {
                    service.destroy(name, true);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                assertThat(service.list()).as("step 5: the record is gone").isEmpty();
                assertThat(PortLedger.claimsOf(DatabaseModel.MODEL_ID, recordId))
                    .as("step 5: the verified destroy freed the claim entirely").isEmpty();
            });
            try {
                docker.inspectContainer(containerName);
                throw new AssertionError("step 5: expected the container to be gone");
            } catch (DockerClient.ApiException e) {
                assertThat(e.isNotFound())
                    .as("step 5: HOST state -- the daemon reports the container absent").isTrue();
            }
            assertThat(new ManagedDatabase(docker, WorkloadNetworkPolicy.forServer(ServerModel.MODE_LOCAL), NetworkPosture.SHARED_BRIDGE)
                    .status(name, ManagedDatabase.Engine.POSTGRES).state())
                .as("step 5: a genuinely gone database reads ABSENT")
                .isEqualTo(ContainerState.ABSENT);
        } finally {
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
        }
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

    private static boolean imagePresent(DockerClient docker, String tag) throws IOException {
        for (Object image : docker.listImages()) {
            Object repoTags = ((Map<?, ?>) image).get("RepoTags");
            if (repoTags instanceof List<?> tags && tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }
}
