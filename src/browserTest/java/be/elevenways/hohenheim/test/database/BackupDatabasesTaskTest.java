package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * Integration test for the {@link BackupDatabases} scheduled task: backs up a running database
 * to a timestamped dump and prunes stale dumps to the retention setting. Isolated SQLite + live
 * Docker daemon (skipped without either).
 */
class BackupDatabasesTaskTest {

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
    void backsUpRunningDatabaseAndPrunesToRetention() throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        SqliteDatasource datasource = freshDatasource();
        DatabaseService service = new DatabaseService(datasource);

        Path backupRoot = Files.createTempDirectory("hohenheim-backups");
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, backupRoot.toString());
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.BACKUP_RETENTION, 1);

        String name = "bk" + System.nanoTime();
        String ephemeralName = "bke" + System.nanoTime();
        try {
            // The backed-up database is durable; the ephemeral one pins the skip.
            service.create(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", false);
            service.create(ephemeralName, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs
            DockerClient.ExecResult seed = docker.exec(Db.supply(datasource, () -> EngineHandles.of(name)),
                List.of("psql", "-U", "appuser", "-d", "appdb", "-c", "CREATE TABLE notes (id int);"),
                List.of("PGPASSWORD=secret123"));
            assertThat(seed.exitCode()).withFailMessage("seed failed: %s", seed.stderr()).isZero();

            // Two stale dumps that retention=1 must prune once a fresh dump is written.
            Path dbDir = backupRoot.resolve(name);
            Files.createDirectories(dbDir);
            Files.writeString(dbDir.resolve("20200101-000001.sql"), "old1");
            Files.writeString(dbDir.resolve("20200101-000002.sql"), "old2");

            BackupDatabases.backupAll(service);

            List<Path> dumps;
            try (Stream<Path> files = Files.list(dbDir)) {
                dumps = files.filter(p -> p.getFileName().toString().endsWith(".sql")).toList();
            }
            assertThat(dumps).hasSize(1);                       // pruned to retention
            String content = Files.readString(dumps.get(0));
            assertThat(content).contains("CREATE TABLE");       // the fresh real dump survived
            assertThat(content).doesNotContain("old1");         // stale dumps were pruned

            // Ephemeral (tmpfs, wiped-on-restart) databases are declared
            // throwaway and must be skipped entirely.
            assertThat(Files.exists(backupRoot.resolve(ephemeralName)))
                .as("ephemeral databases are not dumped")
                .isFalse();
        } finally {
            for (String each : List.of(name, ephemeralName)) {
                try {
                    service.destroy(each, true);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            deleteRecursively(backupRoot);
        }
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-backup-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(ds).migrate().requireSuccess();
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
}
