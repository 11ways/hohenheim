package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.runtime.NetworkPosture;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binary-backup integration tests: Redis (RDB snapshot) and Mongo (mongodump archive) produce
 * their native dump fetched out via the container archive API. Isolated SQLite + live Docker.
 */
class BinaryBackupTest {

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
    private static final String REDIS_IMAGE = "redis:7-alpine";

    /** The password the redis fixtures provision with; the container demands it. */
    private static final String REDIS_PASSWORD = "unused";
    private static final String MONGO_IMAGE = "mongo:7";

    @Test
    void redisBackupProducesRdbSnapshot() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, REDIS_IMAGE);

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = "redis" + System.nanoTime();
        Path dir = Files.createTempDirectory("hohenheim-redis-bk");
        try {
            service.create(name, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", "unused", "unused", true);   // ephemeral: tmpfs
            redis(docker, EngineHandles.of(name), "SET", "foo", "bar");

            Path dump = service.backupToFile(name, dir, "snap");
            assertThat(dump.getFileName().toString()).endsWith(".rdb");
            byte[] bytes = Files.readAllBytes(dump);
            // RDB files begin with the ASCII magic "REDIS" followed by a 4-digit version.
            assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("REDIS");

            // The downloadable artifact (admin UI backup button) carries the same binary dump.
            DatabaseService.BackupDownload download = service.backupDownload(name);
            assertThat(download.filename()).isEqualTo(name + ".rdb");
            assertThat(download.contentType()).isEqualTo("application/octet-stream");
            assertThat(new String(download.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("REDIS");
        } finally {
            cleanup(service, name, dir);
        }
    }

    @Test
    void mongoBackupRestoreRoundTrips() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, MONGO_IMAGE);

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = "mongo" + System.nanoTime();
        Path dir = Files.createTempDirectory("hohenheim-mongo-bk");
        try {
            service.create(name, ManagedDatabase.Engine.MONGO, MONGO_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs
            String container = EngineHandles.of(name);
            mongo(docker, container, "db.getSiblingDB('appdb').things.insertOne({ x: 7 })");

            Path dump = service.backupToFile(name, dir, "dump");
            assertThat(dump.getFileName().toString()).endsWith(".archive");
            assertThat(Files.size(dump)).isGreaterThan(100L);

            mongo(docker, container, "db.getSiblingDB('appdb').things.drop()");   // wipe what the dump holds
            service.restoreFromFile(name, dump);

            DockerClient.ExecResult count = docker.exec(container, List.of(
                "mongosh", "--username", "appuser", "--password", "secret123",
                "--authenticationDatabase", "admin", "--quiet",
                "--eval", "db.getSiblingDB('appdb').things.countDocuments({ x: 7 })"));
            assertThat(count.exitCode()).withFailMessage("count failed: %s", count.stderr()).isZero();
            assertThat(count.stdout().trim()).isEqualTo("1");   // document survived the round-trip
        } finally {
            cleanup(service, name, dir);
        }
    }

    @Test
    void redisBackupRestoreRoundTrips() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, REDIS_IMAGE);

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = "redisrt" + System.nanoTime();
        Path dir = Files.createTempDirectory("hohenheim-redis-rt");
        try {
            service.create(name, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", "unused", "unused", false);   // persistent: restore restarts the container
            String container = EngineHandles.of(name);
            redis(docker, container, "SET", "foo", "bar");

            Path dump = service.backupToFile(name, dir, "snap");

            redis(docker, container, "SET", "foo", "clobbered");   // diverge from the dump
            service.restoreFromFile(name, dump);

            DockerClient.ExecResult get = docker.exec(container, redisCli("GET", "foo"));
            assertThat(get.exitCode()).withFailMessage("redis GET failed: %s", get.stderr()).isZero();
            assertThat(get.stdout().trim()).isEqualTo("bar");   // value survived the round-trip
        } finally {
            cleanup(service, name, dir);
        }
    }

    @Test
    void redisRestoreRejectsEphemeralData() throws IOException {
        // The restore restarts the container, and a tmpfs data dir is wiped by that restart --
        // so an ephemeral redis must reject the restore up-front.
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, REDIS_IMAGE);

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = "redisep" + System.nanoTime();
        Path dir = Files.createTempDirectory("hohenheim-redis-ep");
        try {
            service.create(name, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", "unused", "unused", true);   // ephemeral: tmpfs
            Path dump = service.backupToFile(name, dir, "snap");

            assertThatThrownBy(() -> service.restoreFromFile(name, dump))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("persistent");
        } finally {
            cleanup(service, name, dir);
        }
    }

    @Test
    void redisRestoreRejectsNonRdbInput() throws IOException {
        // The RDB magic check runs before any Docker interaction, so no daemon is needed.
        ManagedDatabase databases = new ManagedDatabase(new DockerClient());
        Path dummy = Files.createTempFile("hohenheim-redis-restore", ".rdb");
        try {
            Files.writeString(dummy, "not an rdb file", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> databases.restoreFromFile(
                    "any-handle", ManagedDatabase.Engine.REDIS, "u", "p", "d", dummy))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REDIS magic");
        } finally {
            Files.deleteIfExists(dummy);
        }
    }

    /**
     * A redis-cli that AUTHENTICATES. Managed redis containers run
     * {@code redis-server --requirepass <password>} (ManagedDatabase.Engine.containerCommandTemplate), so
     * an unauthenticated client gets "NOAUTH Authentication required" -- ON STDOUT, with
     * EXIT CODE 0. Every exitCode-only assertion around a redis-cli call therefore passed
     * while the command did nothing at all; only the round trip, which asserts a VALUE,
     * could see it. The password is the one the fixtures pass to create().
     */
    private static void redis(DockerClient docker, String container, String... command) throws IOException {
        DockerClient.ExecResult result = docker.exec(container, redisCli(command));
        assertThat(result.exitCode()).withFailMessage("redis-cli failed: %s", result.stderr()).isZero();
        assertThat(result.stdout()).withFailMessage("redis-cli refused: %s", result.stdout())
            .doesNotContain("NOAUTH");
    }

    /** {@code redis-cli -a <password>} plus the command; --no-auth-warning keeps stderr clean. */
    private static List<String> redisCli(String... command) {
        List<String> full = new ArrayList<>(List.of(
            "redis-cli", "-a", REDIS_PASSWORD, "--no-auth-warning"));
        full.addAll(List.of(command));
        return full;
    }

    private static void mongo(DockerClient docker, String container, String eval) throws IOException {
        DockerClient.ExecResult result = docker.exec(container, List.of(
            "mongosh", "--username", "appuser", "--password", "secret123",
            "--authenticationDatabase", "admin", "--quiet", "--eval", eval));
        assertThat(result.exitCode()).withFailMessage("mongo eval failed: %s", result.stderr()).isZero();
    }

    private static void cleanup(DatabaseService service, String name, Path dir) throws IOException {
        try {
            service.destroy(name, true);
        } catch (IOException ignored) {
            // best effort
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-binbackup-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(ds).migrate().requireSuccess();
        // The controller identity (and every daemon resource name derived from it) and
        // the owned-instance lookups both resolve through the CURRENT datasource, so this
        // one has to BE the current one for the whole test.
        Datasources.register(Datasources.DEFAULT, ds);
        HohenheimTestRuntime.ensureBooted();
        return ds;
    }
}
