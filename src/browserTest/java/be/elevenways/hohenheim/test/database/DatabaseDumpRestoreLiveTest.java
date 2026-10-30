package be.elevenways.hohenheim.test.database;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.BackupStatus;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseRestores;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A stored PostgreSQL dump restores on a real engine: into a NEW database of the same kind and image, which then holds
 * the dumped rows, and in place, which first stores a fresh pre-restore dump and then brings the dumped rows back.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class DatabaseDumpRestoreLiveTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String PG_IMAGE = "postgres:17-alpine";
    private static final String PASSWORD = "secret123-dump";

    private static PrivateNetns netns;

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        netns = PrivateNetns.installEnforcing();
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: record-backed provisioning refuses without an enforceable policy");
    }

    @AfterAll
    static void restorePolicy() {
        PrivateNetns.uninstall(netns);
        netns = null;
    }

    @Test
    void aStoredDumpRestoresIntoANewDatabaseAndInPlaceBehindAFreshDump() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, PG_IMAGE);

        DatabaseService service = new DatabaseService();
        String name = "dumprt" + System.nanoTime();
        String copy = name + "-copy";
        Path work = Files.createTempDirectory("dump-restore-live");
        try (DumpTargetFixture target = DumpTargetFixture.install("dump-restore-live")) {
            // 1. A persistent database holding one row, dumped to the target.
            service.create(name, ManagedDatabase.Engine.POSTGRES, PG_IMAGE, "appuser", PASSWORD, "appdb", false,
                ServerModel.nameOf(ServerModel.localServerId()), ResourceLimits.none(),
                DatabaseModel.PLACEMENT_DEDICATED);
            psql(docker, name, PASSWORD, "CREATE TABLE things (x integer); INSERT INTO things VALUES (7);");
            Row dump = DatabaseBackups.backUp(service, name);
            int dumpId = dump.get(DatabaseDumpModel.ID);

            // 2. The database moves on after the dump.
            psql(docker, name, PASSWORD, "INSERT INTO things VALUES (8);");
            assertThat(psql(docker, name, PASSWORD, "SELECT x FROM things ORDER BY x")).as("step 2: moved on")
                .isEqualTo("7\n8");

            // 3. Restore into a new database: same kind and image, the dumped rows, and the source untouched.
            Row created = DatabaseRestores.restoreToNew(service, dumpId, copy);
            assertThat((String) created.get(DatabaseModel.ENGINE)).isEqualTo(DatabaseModel.ENGINE_POSTGRES);
            assertThat((String) created.get(DatabaseModel.IMAGE)).as("step 3: the dump's own version")
                .isEqualTo(PG_IMAGE);
            String copyPassword = Models.get(DatabaseModel.class).findByName(copy).get(DatabaseModel.DB_PASSWORD);
            assertThat(psql(docker, copy, copyPassword, "SELECT x FROM things ORDER BY x"))
                .as("step 3: the new database holds the dumped rows").isEqualTo("7");
            assertThat(psql(docker, name, PASSWORD, "SELECT x FROM things ORDER BY x"))
                .as("step 3: the source keeps running as it was").isEqualTo("7\n8");

            // 3b. A second restore into the same name is refused by the create, and the copy keeps its rows: the
            //     refused restore removes nothing of what another restore created.
            Throwable clash = catchThrowable(() -> DatabaseRestores.restoreToNew(service, dumpId, copy));
            assertThat(clash).as("step 3b: refused by name").isInstanceOfSatisfying(Violations.class, refused ->
                assertThat(refused.all().get(0).message().key()).isEqualTo("database_name_taken"));
            assertThat((Integer) Models.get(DatabaseModel.class).findByName(copy).get(DatabaseModel.ID))
                .as("step 3b: the copy is the same record").isEqualTo(created.get(DatabaseModel.ID));
            assertThat(psql(docker, copy, copyPassword, "SELECT x FROM things ORDER BY x"))
                .as("step 3b: and still holds its rows").isEqualTo("7");

            // 4. Restore in place: a fresh dump first, then the dumped rows back.
            Row before = DatabaseRestores.restoreInPlace(service, dumpId);
            assertThat((Integer) before.get(DatabaseDumpModel.ID)).as("step 4: the pre-restore dump is newer")
                .isGreaterThan(dumpId);
            assertThat((String) before.get(DatabaseDumpModel.STATUS)).isEqualTo(BackupStatus.COMPLETE.key());
            assertThat(psql(docker, name, PASSWORD, "SELECT x FROM things ORDER BY x"))
                .as("step 4: the dumped rows are back").isEqualTo("7");
            Path saved = DatabaseBackups.fetchVerified(before, work);
            assertThat(Files.readString(saved)).as("step 4: the pre-restore dump holds what the restore replaced")
                .contains("\n7\n8\n");
            assertThat(DatabaseBackups.stored(created.get(DatabaseModel.ID))).as("step 4: listing is per database")
                .isEmpty();

            // 5. A load failing part way leaves the database as it was: the --clean drops roll back with it.
            Path failing = work.resolve("failing.sql");
            Files.writeString(failing, "DROP TABLE IF EXISTS things;\nCREATE TABLE things (x integer);\n"
                + "SELECT no_such_function();\n");
            assertThat(catchThrowable(() -> service.restoreFromFile(name, failing)))
                .as("step 5: the load fails").isInstanceOf(IOException.class);
            assertThat(psql(docker, name, PASSWORD, "SELECT x FROM things ORDER BY x"))
                .as("step 5: and nothing of it stays").isEqualTo("7");
        } finally {
            for (String each : List.of(copy, name)) {
                try {
                    service.destroy(each, true);
                } catch (IOException | RuntimeException ignored) {
                    // best effort
                }
            }
            FileTrees.deleteQuietly(work);
        }
    }

    /** @return the trimmed tuples-only output of one statement run as the database's own user */
    private static String psql(DockerClient docker, String database, String password, String sql) throws IOException {
        DockerClient.ExecResult result = docker.exec(EngineHandles.of(database),
            List.of("psql", "-U", "appuser", "-d", "appdb", "-v", "ON_ERROR_STOP=1", "-tAc", sql),
            List.of("PGPASSWORD=" + password));
        assertThat(result.exitCode()).withFailMessage("psql failed: %s", result.stderr()).isZero();
        return result.stdout().trim();
    }
}
