package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.migration.M015_CreateManagedDatabases;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void redisRestoreIsUnsupported() throws IOException {
        // Redis loads its RDB only at container startup, so live restore isn't possible; the
        // engine rejects it up-front (before any Docker interaction), so no daemon is needed.
        ManagedDatabase databases = new ManagedDatabase(new DockerClient());
        Path dummy = Files.createTempFile("hohenheim-redis-restore", ".rdb");
        try {
            assertThatThrownBy(() -> databases.restoreFromFile(
                    "any", ManagedDatabase.Engine.REDIS, "u", "p", "d", dummy))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("redis");
        } finally {
            Files.deleteIfExists(dummy);
        }
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
            List.of(M015_CreateManagedDatabases::new)).migrate();
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
