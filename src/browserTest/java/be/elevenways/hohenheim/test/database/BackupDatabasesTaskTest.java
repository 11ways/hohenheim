package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@link BackupDatabases} scheduled task: dumps a running database to the backup target and
 * prunes its stored dumps to the retention setting, skipping a temporary one. Isolated SQLite + live Docker daemon.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
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
    void backsUpRunningDatabaseAndPrunesToRetention() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        DatabaseService service = new DatabaseService();
        Integer previousRetention = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_RETENTION, 1);

        String name = "bk" + System.nanoTime();
        String ephemeralName = "bke" + System.nanoTime();
        try (DumpTargetFixture target = DumpTargetFixture.install("nightly-dump-target")) {
            // The backed-up database is durable; the ephemeral one pins the skip.
            service.create(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123-durable", "appdb", false);
            service.create(ephemeralName, ManagedDatabase.Engine.POSTGRES, PG_IMAGE,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs
            DockerClient.ExecResult seed = docker.exec(EngineHandles.of(name),
                List.of("psql", "-U", "appuser", "-d", "appdb", "-c", "CREATE TABLE notes (id int);"),
                List.of("PGPASSWORD=secret123-durable"));
            assertThat(seed.exitCode()).withFailMessage("seed failed: %s", seed.stderr()).isZero();
            Integer databaseId = Models.get(DatabaseModel.class).findByName(name).get(DatabaseModel.ID);

            // Two nightly runs at retention 1: the newest dump stays, the first leaves the target with its row.
            BackupDatabases.backupAll(service);
            Row first = Models.get(DatabaseDumpModel.class).find()
                .where(DatabaseDumpModel.DATABASE_ID.eq(databaseId)).first();
            Path firstArtifact = target.artifactOf(first);
            assertThat(Files.exists(firstArtifact)).as("the first dump is on the target").isTrue();
            BackupDatabases.backupAll(service);

            List<DatabaseBackups.Stored> dumps = DatabaseBackups.stored(databaseId);
            assertThat(dumps).as("pruned to retention").hasSize(1);
            assertThat(dumps.get(0).id()).as("the fresh dump survived").isGreaterThan(first.get(DatabaseDumpModel.ID));
            assertThat(Files.exists(firstArtifact)).as("the pruned dump left the target").isFalse();
            Path plain = DatabaseBackups.fetchVerified(Models.get(DatabaseDumpModel.class).findById(dumps.get(0).id()),
                Files.createTempDirectory("nightly-fetch"));
            assertThat(Files.readString(plain)).as("a real dump").contains("CREATE TABLE");

            // Ephemeral (tmpfs, wiped-on-restart) databases are declared throwaway and are skipped entirely.
            Integer ephemeralId = Models.get(DatabaseModel.class).findByName(ephemeralName).get(DatabaseModel.ID);
            assertThat(Models.get(DatabaseDumpModel.class).find()
                .where(DatabaseDumpModel.DATABASE_ID.eq(ephemeralId)).count())
                .as("ephemeral databases are not dumped").isZero();
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_RETENTION, previousRetention);
            for (String each : List.of(name, ephemeralName)) {
                try {
                    service.destroy(each, true);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }
}
