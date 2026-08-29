package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Mongo readiness probe against REAL engines: a managed database on {@code mongo:4.4}
 * (legacy {@code mongo} shell, the last engine without AVX) and on {@code mongo:7}
 * ({@code mongosh}) both reach ready through the one probe, and the ready engine really
 * authenticates the provisioned user. The 4.4 half is what starfleet's QEMU CPU needs, and
 * what timed out before the probe learned the legacy shell.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class MongoShellProbeLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
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

    @ParameterizedTest(name = "{0} reaches ready through the shell it ships")
    @ValueSource(strings = { "mongo:4.4", "mongo:7" })
    void aManagedMongoReachesReadyWhicheverShellTheImageShips(String image) throws IOException {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, image);

        DatabaseService service = new DatabaseService(freshDatasource());
        String name = "probe" + System.nanoTime();
        try {
            // 1. create() blocks on awaitReady: a probe that cannot run its shell times
            //    out here, which is exactly the failure starfleet recorded for 4.4.
            service.create(name, ManagedDatabase.Engine.MONGO, image,
                "appuser", "secret123", "appdb", true);   // ephemeral: tmpfs

            // 2. Ready means AUTHENTICATED: the same script, run once more by hand,
            //    answers the ping as the provisioned root user.
            DockerClient.ExecResult probe = docker.exec(EngineHandles.of(name),
                ManagedDatabase.Engine.MONGO.readyCommand("appuser", "secret123", "appdb"),
                ManagedDatabase.Engine.MONGO.readyEnv("secret123"));
            assertThat(probe.exitCode())
                .withFailMessage("step 2: probe failed on %s: %s", image, probe.stderr()).isZero();
            assertThat(probe.stdout().trim())
                .as("step 2: the ping answered ok=1 on " + image).isEqualTo("1");

            // 3. The shell that answered is the one the image ships.
            DockerClient.ExecResult which = docker.exec(EngineHandles.of(name),
                List.of("sh", "-c", "command -v mongosh || command -v mongo"));
            assertThat(which.stdout().trim())
                .as("step 3: " + image + " answered through its own shell")
                .endsWith(image.startsWith("mongo:4") ? "/mongo" : "/mongosh");
        } finally {
            try {
                service.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static SqliteDatasource freshDatasource() throws IOException {
        File db = File.createTempFile("hohenheim-mongoprobe-test", ".db");
        db.delete();
        db.deleteOnExit();
        SqliteDatasource ds = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(ds).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, ds);
        HohenheimTestRuntime.ensureBooted();
        return ds;
    }
}
