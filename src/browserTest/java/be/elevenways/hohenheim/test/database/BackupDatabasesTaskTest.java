package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.migration.M015_CreateManagedDatabases;
import be.elevenways.hohenheim.migration.M016_AddDatabaseStatus;
import be.elevenways.hohenheim.migration.M018_AddDatabaseServer;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for the {@link BackupDatabases} scheduled task: backs up a running database
 * to a timestamped dump and prunes stale dumps to the retention setting. Isolated SQLite + live
 * Docker daemon (skipped without either).
 */
class BackupDatabasesTaskTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String PG_IMAGE = "postgres:17-alpine";

    @Test
    void backsUpRunningDatabaseAndPrunesToRetention() throws IOException {
        assumeTrue(Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        assumeTrue(imagePresent(docker, PG_IMAGE), PG_IMAGE + " not present locally");

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(docker, datasource);

        Path backupRoot = Files.createTempDirectory("hohenheim-backups");
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, backupRoot.toString());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.BACKUP_RETENTION, 1);

        String name = "bk" + System.nanoTime();
        try {
            service.create(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs, no btrfs I/O
            DockerClient.ExecResult seed = docker.exec("hohenheim-db-" + name,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-c", "CREATE TABLE notes (id int);"),
                List.of("PGPASSWORD=secret123"));
            assertThat(seed.exitCode()).withFailMessage("seed failed: %s", seed.stderr()).isZero();

            // Two stale dumps that retention=1 must prune once a fresh dump is written.
            Path dbDir = backupRoot.resolve(name);
            Files.createDirectories(dbDir);
            Files.writeString(dbDir.resolve("20200101-000001.sql"), "old1");
            Files.writeString(dbDir.resolve("20200101-000002.sql"), "old2");

            new BackupDatabases(service).run();

            List<Path> dumps;
            try (Stream<Path> files = Files.list(dbDir)) {
                dumps = files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
            }
            assertThat(dumps).hasSize(1);                       // pruned to retention
            String content = Files.readString(dumps.get(0));
            assertThat(content).contains("CREATE TABLE");       // the fresh real dump survived
            assertThat(content).doesNotContain("old1");         // stale dumps were pruned
        } finally {
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
            deleteRecursively(backupRoot);
        }
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-backup-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner((MigrationCapableDatasource) ds,
            List.of(M015_CreateManagedDatabases::new, M016_AddDatabaseStatus::new,
                M018_AddDatabaseServer::new)).migrate();
        HohenheimTestRuntime.ensureBooted();
        return ds;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
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
