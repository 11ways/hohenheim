package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.migration.M015_CreateManagedDatabases;
import be.elevenways.hohenheim.migration.M016_AddDatabaseStatus;
import be.elevenways.hohenheim.migration.M018_AddDatabaseServer;
import be.elevenways.hohenheim.migration.M029_AddDatabaseLimits;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.SqliteDatasource;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Binary-backup integration tests: Redis (RDB snapshot) and Mongo (mongodump archive) produce
 * their native dump fetched out via the container archive API. Isolated SQLite + live Docker.
 */
class BinaryBackupTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final String MONGO_IMAGE = "mongo:7";

    @Test
    void redisBackupProducesRdbSnapshot() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, REDIS_IMAGE), REDIS_IMAGE + " not present locally");

        DatabaseService service = new DatabaseService(docker, freshDatasource());
        String name = "redis" + System.nanoTime();
        Path dir = Files.createTempDirectory("hohenheim-redis-bk");
        try {
            service.create(name, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", "unused", "unused", true);   // ephemeral: tmpfs
            DockerClient.ExecResult set = docker.exec("hohenheim-db-" + name,
                List.of("redis-cli", "SET", "foo", "bar"));
            assertThat(set.exitCode()).withFailMessage("redis SET failed: %s", set.stderr()).isZero();

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
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, MONGO_IMAGE), MONGO_IMAGE + " not present locally");

        DatabaseService service = new DatabaseService(docker, freshDatasource());
        String name = "mongo" + System.nanoTime();
        String container = "hohenheim-db-" + name;
        Path dir = Files.createTempDirectory("hohenheim-mongo-bk");
        try {
            service.create(name, ManagedDatabase.Engine.MONGO, MONGO_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs
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
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, REDIS_IMAGE), REDIS_IMAGE + " not present locally");

        DatabaseService service = new DatabaseService(docker, freshDatasource());
        String name = "redisrt" + System.nanoTime();
        String container = "hohenheim-db-" + name;
        Path dir = Files.createTempDirectory("hohenheim-redis-rt");
        try {
            service.create(name, ManagedDatabase.Engine.REDIS, REDIS_IMAGE,
                "unused", "unused", "unused", false);   // persistent: restore restarts the container
            redis(docker, container, "SET", "foo", "bar");

            Path dump = service.backupToFile(name, dir, "snap");

            redis(docker, container, "SET", "foo", "clobbered");   // diverge from the dump
            service.restoreFromFile(name, dump);

            DockerClient.ExecResult get = docker.exec(container, List.of("redis-cli", "GET", "foo"));
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
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, REDIS_IMAGE), REDIS_IMAGE + " not present locally");

        DatabaseService service = new DatabaseService(docker, freshDatasource());
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
                    "any", ManagedDatabase.Engine.REDIS, "u", "p", "d", dummy))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("REDIS magic");
        } finally {
            Files.deleteIfExists(dummy);
        }
    }

    private static void redis(DockerClient docker, String container, String... command) throws IOException {
        List<String> full = new ArrayList<>(List.of("redis-cli"));
        full.addAll(List.of(command));
        DockerClient.ExecResult result = docker.exec(container, full);
        assertThat(result.exitCode()).withFailMessage("redis-cli failed: %s", result.stderr()).isZero();
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
        new MigrationRunner((MigrationCapableDatasource) ds,
            List.of(M015_CreateManagedDatabases::new, M016_AddDatabaseStatus::new,
                M018_AddDatabaseServer::new, M029_AddDatabaseLimits::new)).migrate();
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
