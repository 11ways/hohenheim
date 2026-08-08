package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.database.DatabaseContainerKind;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The managed-database tier ON the canonical runtime-resource contract, against a real
 * Docker daemon: a database's engine IS an owned {@code hohenheim:database_container}
 * instance, and the mechanisms the instance tier already provides now apply to it.
 *
 * AIDEV-NOTE: this class used to drive {@code ManagedDatabase.provision/status/destroy}
 * -- a second, weaker copy of the instance tier's lifecycle. Those methods are GONE, so
 * the tests drive the product lane ({@link DatabaseService}) and assert the instance
 * facts at the DAEMON: owner labels naming the instance, the engine's own hardening
 * profile, the ledger claim, and the data volume that survives the lowering.
 */
class ManagedDatabaseTest {

    private static PrivateNetns netns;
    private static SqliteDatasource datasource;

    @BeforeAll
    static void fixture() throws IOException {
        datasource = freshDatasource();
        netns = PrivateNetns.installEnforcing();
    }

    @AfterAll
    static void tearDown() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String PG_IMAGE = "postgres:17-alpine";
    private static final String MYSQL_IMAGE = "mysql:8.0";
    private static final String REDIS_IMAGE = "redis:7-alpine";

    private void requireFixture(DockerClient docker, String image) throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        assumeTrue(netns != null,
            "no private netns: a record-backed database refuses without an enforceable policy");
        assumeTrue(imagePresent(docker, image), image + " not present locally");
    }

    /**
     * THE lowering journey: provisioning a database produces an OWNED INSTANCE, and every
     * fact the instance tier guarantees is asserted at the daemon, not through our own
     * bookkeeping.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aDatabasesEngineIsAnOwnedInstanceWithEveryInstanceTierMechanism() throws IOException {
        DockerClient docker = new DockerClient();
        requireFixture(docker, PG_IMAGE);

        DatabaseService service = new DatabaseService(datasource);
        String name = "test" + System.nanoTime();
        try {
            // ephemeral=true -> the data dir is a RAM tmpfs mount, so Postgres initdb never
            // fsync-storms the btrfs root (which previously stalled it for minutes).
            ManagedDatabase.Connection conn = service.create(name,
                ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);

            assertThat(conn.host()).isEqualTo("127.0.0.1");
            assertThat(conn.port()).isGreaterThan(0);
            assertThat(conn.database()).isEqualTo("appdb");

            Db.run(datasource, () -> {
                Row database = Models.get(DatabaseModel.class).findByName(name);
                Integer databaseId = database.get(DatabaseModel.ID);

                // 1. The record OWNS an instance of the database_container kind, through
                //    the GeneratedRows attribution -- not a container it named itself.
                Row instance = DatabaseInstances.owned(databaseId);
                assertThat(instance).as("step 1: the database owns an engine instance").isNotNull();
                assertThat((String) instance.get(InstanceModel.KIND))
                    .as("step 1: of the lowered kind")
                    .isEqualTo(DatabaseContainerKind.ID.toString());
                assertThat((String) instance.get(InstanceModel.GENERATED_BY))
                    .as("step 1: attributed to the database tier")
                    .isEqualTo(DatabaseInstances.SOURCE);
                assertThat((Integer) instance.get(InstanceModel.GENERATED_FOR_ID))
                    .as("step 1: and to THIS database record").isEqualTo(databaseId);
                assertThat((String) instance.get(InstanceModel.STATUS))
                    .as("step 1: stamped running by the fenced outcome write")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);

                int instanceId = instance.get(InstanceModel.ID);
                String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);

                // 2. DAEMON TRUTH: the container is the instance's, labelled with the
                //    INSTANCE as owner -- the pre-lowering container was labelled with the
                //    database record, and a stale label here would break every attribution
                //    the reconciler and the replace-only-our-own rule depend on.
                Map<String, Object> info = ioQuiet(() -> docker.inspectContainer(handle));
                assertThat(info).as("step 2: the daemon knows the instance handle").isNotNull();
                assertThat(((Map<String, Object>) info.get("State")).get("Running"))
                    .as("step 2: and reports it running").isEqualTo(Boolean.TRUE);
                Map<String, Object> config = (Map<String, Object>) info.get("Config");
                OwnerLabels.Owner owner = OwnerLabels.parse((Map<?, ?>) config.get("Labels"));
                assertThat(owner).as("step 2: the container carries owner labels").isNotNull();
                assertThat(owner.model())
                    .as("step 2: naming the INSTANCE, not the database record")
                    .isEqualTo(InstanceModel.MODEL_ID);
                assertThat(owner.id()).isEqualTo(String.valueOf(instanceId));

                // 3. The PRE-LOWERING name-keyed container does not exist: the lowering
                //    replaced the runtime, it did not leave a second one running.
                assertThat(ioQuiet(() -> docker.inspectContainer(
                        ControllerScope.handle(ControllerScope.KIND_DB, name))))
                    .as("step 3: no pre-lowering db-{name} container survives").isNull();

                // 4. The ENGINE's declared hardening reached the kernel-facing spec, and
                //    the declared egress-NONE network is the only one it is on.
                Map<String, Object> hostConfig = (Map<String, Object>) info.get("HostConfig");
                assertThat((List<?>) hostConfig.get("CapDrop"))
                    .as("step 4: the engine's SERVICE profile dropped capabilities")
                    .isNotEmpty();
                Map<String, Object> networks = (Map<String, Object>)
                    ((Map<String, Object>) info.get("NetworkSettings")).get("Networks");
                assertThat(networks.keySet())
                    .as("step 4: exactly one private workload network, never the shared bridge")
                    .doesNotContain("bridge").hasSize(1);

                // 5. The engine actually answers on the published loopback port.
                ioQuiet(() -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(conn.host(), conn.port()), 2000);
                    }
                    return null;
                });

                // 6. The data volume is keyed to the DATABASE NAME, not the instance id:
                //    the runtime row may be replaced, the data may not.
                List<?> mounts = (List<?>) info.get("Mounts");
                assertThat(mounts).as("step 6: the ephemeral data dir is a tmpfs mount")
                    .anySatisfy(mount -> assertThat(((Map<?, ?>) mount).get("Type"))
                        .isEqualTo("tmpfs"));
                assertThat(DatabaseInstances.dataVolumeOf(name))
                    .as("step 6: and a persistent one would be keyed by NAME")
                    .isEqualTo(ControllerScope.handle(ControllerScope.KIND_DB, name) + "-data");
            });

            service.destroy(name, true);
            assertThat(ioQuiet(() -> docker.inspectContainer(
                    ControllerScope.handle(ControllerScope.KIND_INSTANCE, 0))))
                .as("sanity: a bogus handle reads absent").isNull();
        } finally {
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * THE readiness-probe trap, measured at the daemon rather than assumed: a redis-cli
     * that is refused for missing authentication exits ZERO and prints its refusal to
     * STDOUT, so an exit-code-only readiness probe reports a server "ready" that answered
     * nothing. The engine docblock claimed the opposite for months.
     */
    @Test
    void theRedisReadinessProbeIsNotFooledByAnExitZeroRefusal() throws IOException {
        DockerClient docker = new DockerClient();
        requireFixture(docker, REDIS_IMAGE);

        DatabaseService service = new DatabaseService(datasource);
        String name = "noauth" + System.nanoTime();
        try {
            service.create(name, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", "probepw", "unused", true);
            String container = Db.supply(datasource, () -> EngineHandles.of(name));

            // 1. THE MEASUREMENT: unauthenticated redis-cli exits 0 and refuses on stdout.
            DockerClient.ExecResult refused = docker.exec(container,
                List.of("redis-cli", "-p", "6379", "ping"), List.of());
            assertThat(refused.exitCode())
                .as("step 1: redis-cli exits ZERO even when it was refused").isZero();
            assertThat(refused.stdout())
                .as("step 1: and the refusal is on STDOUT, where no exit code shows it")
                .contains("NOAUTH");

            // 2. THE CONSEQUENCE: the readiness gate must refuse that same answer. An
            //    exit-code-only probe would return immediately here and report ready.
            assertThatThrownBy(() -> ManagedDatabase.awaitReady(docker, container,
                    ManagedDatabase.Engine.REDIS, "unused", "", "unused", 3_000))
                .as("step 2: readiness refuses a server that only answered NOAUTH")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("NOAUTH");

            // 3. THE POSITIVE ANCHOR: with the real password the same gate passes fast,
            //    so step 2 measured the anchor and not a broken probe.
            ManagedDatabase.awaitReady(docker, container, ManagedDatabase.Engine.REDIS,
                "unused", "probepw", "unused", 10_000);
        } finally {
            destroyQuietly(service, name);
        }
    }

    /**
     * The generated-only declaration: {@code database_container} cannot be authored from
     * the standalone instance surface at all, so the admin instance form can never become
     * a second authority over a managed database's engine.
     */
    @Test
    void theEngineKindCannotBeAuthoredOutsideTheDatabaseTier() {
        Db.run(datasource, () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, "hand-authored-engine");
            row.set(InstanceModel.KIND, DatabaseContainerKind.ID.toString());
            row.set(InstanceModel.SETTINGS, Map.of("engine", "redis"));
            assertThatThrownBy(() -> Models.get(InstanceModel.class).save(row))
                .as("a standalone create of the engine kind is a NAMED refusal")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("Database container");
            assertThat(Models.get(InstanceModel.class).find()
                    .where(InstanceModel.NAME.eq("hand-authored-engine")).all())
                .as("and nothing was written").isEmpty();
        });
    }

    /**
     * The replace path must never destroy what it cannot attribute: a same-named
     * container WITHOUT this instance's owner labels is a loud, named refusal, and the
     * foreign container survives untouched on the host.
     */
    @Test
    void provisionRefusesToReplaceAForeignSameNamedContainer() throws IOException {
        DockerClient docker = new DockerClient();
        requireFixture(docker, PG_IMAGE);
        assumeTrue(imagePresent(docker, "alpine:latest"), "alpine:latest not present locally");

        DatabaseService service = new DatabaseService(datasource);
        String name = "foreign" + System.nanoTime();
        // 1. Provision once so the record owns an instance and we know its handle.
        service.create(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
            "appuser", "secret123", "appdb", true);
        String handle = Db.supply(datasource, () -> EngineHandles.of(name));
        try {
            // 2. Replace the engine container with an UNLABELLED squatter on the same name
            //    (what a force-removing path would have destroyed without a thought).
            docker.removeContainer(handle, true);
            docker.createContainer(handle, Map.of(
                "Image", "alpine:latest", "Cmd", List.of("sleep", "300")),
                ContainerHardening.STRICT);

            // 3. Re-provisioning over it is a named refusal, not a silent replace.
            //    AIDEV-NOTE: this used to re-call service.create, which no longer reaches
            //    the runtime at all -- the create lane is INSERT-only and refuses a taken
            //    name (database_name_taken) before any daemon call. That refusal is a
            //    DIFFERENT and much earlier gate, so asserting it here would have quietly
            //    stopped exercising the attribution one. The re-provision path is
            //    DatabaseInstances.deploy over the EXISTING record, which is what every
            //    real re-deploy runs.
            Row record = Db.supply(datasource,
                () -> Models.get(DatabaseModel.class).findByName(name));
            assertThatThrownBy(() -> Db.supply(datasource, () -> {
                    try {
                        return DatabaseInstances.deploy(record, ResourceLimits.none());
                    } catch (IOException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                }))
                .as("step 3: the refusal is loud and names the attribution failure")
                .hasMessageContaining("not attributably ours");

            // 4. HOST state: the foreign container survives, unlabelled and unharmed.
            Map<String, Object> survivor = docker.inspectContainer(handle);
            assertThat(survivor).as("step 4: the foreign container still exists").isNotNull();
            Map<?, ?> config = (Map<?, ?>) survivor.get("Config");
            assertThat(OwnerLabels.parse((Map<?, ?>) config.get("Labels")))
                .as("step 4: still unattributable, so still nobody's to remove").isNull();
        } finally {
            try {
                docker.removeContainer(handle, true);
            } catch (IOException ignored) {
                // best effort
            }
            Db.run(datasource, () -> Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.NAME.eq(name)).delete());
        }
    }

    @Test
    void backsUpPostgresAsSql() throws IOException {
        DockerClient docker = new DockerClient();
        requireFixture(docker, PG_IMAGE);

        DatabaseService service = new DatabaseService(datasource);
        String name = "buptest" + System.nanoTime();
        try {
            service.create(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs, no btrfs I/O
            String container = Db.supply(datasource, () -> EngineHandles.of(name));

            // Seed a table so the dump has identifiable content (also exercises exec-with-env).
            DockerClient.ExecResult create = docker.exec(container,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-c", "CREATE TABLE widgets (id int);"),
                List.of("PGPASSWORD=secret123"));
            assertThat(create.exitCode()).withFailMessage("seed failed: %s", create.stderr()).isZero();

            String dump = new ManagedDatabase(new ServerService().clientFor(ServerService.LOCAL))
                .backup(container, ManagedDatabase.Engine.POSTGRES, "appuser", "secret123", "appdb");
            assertThat(dump).contains("CREATE TABLE");
            assertThat(dump).contains("widgets");
        } finally {
            destroyQuietly(service, name);
        }
    }

    @Test
    void backupRestoreRoundTripsPostgres() throws IOException {
        DockerClient docker = new DockerClient();
        requireFixture(docker, PG_IMAGE);

        DatabaseService service = new DatabaseService(datasource);
        String name = "rttest" + System.nanoTime();
        List<String> env = List.of("PGPASSWORD=secret123");
        try {
            service.create(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs, no btrfs I/O
            String container = Db.supply(datasource, () -> EngineHandles.of(name));
            ManagedDatabase engine =
                new ManagedDatabase(new ServerService().clientFor(ServerService.LOCAL));

            psql(docker, container, env, "CREATE TABLE notes (id int); INSERT INTO notes VALUES (7);");
            String dump = engine.backup(container, ManagedDatabase.Engine.POSTGRES,
                "appuser", "secret123", "appdb");

            psql(docker, container, env, "DROP TABLE notes;");   // wipe what the dump holds

            engine.restore(container, ManagedDatabase.Engine.POSTGRES,
                "appuser", "secret123", "appdb", dump);

            DockerClient.ExecResult check = docker.exec(container,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-tAc", "SELECT count(*) FROM notes WHERE id=7"),
                env);
            assertThat(check.exitCode()).withFailMessage("query failed: %s", check.stderr()).isZero();
            assertThat(check.stdout().trim()).isEqualTo("1");   // row survived the round-trip
        } finally {
            destroyQuietly(service, name);
        }
    }

    private static void psql(DockerClient docker, String containerName, List<String> env, String sql)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(containerName,
            List.of("psql", "-v", "ON_ERROR_STOP=1", "-U", "appuser", "-d", "appdb", "-c", sql), env);
        assertThat(result.exitCode()).withFailMessage("psql failed: %s", result.stderr()).isZero();
    }

    @Test
    void backupRestoreRoundTripsMysql() throws IOException {
        DockerClient docker = new DockerClient();
        requireFixture(docker, MYSQL_IMAGE);

        DatabaseService service = new DatabaseService(datasource);
        String name = "mytest" + System.nanoTime();
        List<String> env = List.of("MYSQL_PWD=secret123");
        try {
            service.create(name, ManagedDatabase.Engine.MYSQL, MYSQL_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs, no btrfs I/O
            String container = Db.supply(datasource, () -> EngineHandles.of(name));
            ManagedDatabase engine =
                new ManagedDatabase(new ServerService().clientFor(ServerService.LOCAL));

            mysql(docker, container, env, "CREATE TABLE notes (id int); INSERT INTO notes VALUES (7);");
            String dump = engine.backup(container, ManagedDatabase.Engine.MYSQL,
                "appuser", "secret123", "appdb");

            mysql(docker, container, env, "DROP TABLE notes;");

            engine.restore(container, ManagedDatabase.Engine.MYSQL,
                "appuser", "secret123", "appdb", dump);

            DockerClient.ExecResult check = docker.exec(container,
                List.of("mysql", "-u", "appuser", "appdb", "-N", "-B", "-e", "SELECT count(*) FROM notes WHERE id=7"),
                env);
            assertThat(check.exitCode()).withFailMessage("query failed: %s", check.stderr()).isZero();
            assertThat(check.stdout().trim()).isEqualTo("1");   // row survived the round-trip
        } finally {
            destroyQuietly(service, name);
        }
    }

    private static void mysql(DockerClient docker, String containerName, List<String> env, String sql)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(containerName,
            List.of("mysql", "-u", "appuser", "appdb", "-e", sql), env);
        assertThat(result.exitCode()).withFailMessage("mysql failed: %s", result.stderr()).isZero();
    }

    private static void destroyQuietly(DatabaseService service, String name) {
        try {
            service.destroy(name, true);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private interface IoBody<T> {
        T run() throws IOException;
    }

    /** @return the body's value, or null when the daemon answered "not there" */
    private static <T> T ioQuiet(IoBody<T> body) {
        try {
            return body.run();
        } catch (IOException absent) {
            return null;
        }
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-manageddb-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(ds).migrate().requireSuccess();
        // Names and labels are controller-namespaced, and the namespace resolves through
        // the CURRENT datasource -- so this one must BE the current one.
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
