package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.test.database.DumpTargetFixture;
import be.elevenways.hohenheim.test.database.EngineHandles;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.thread.Racers;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for the admin-UI restore upload: a multipart POST to
 * /databases/:name/restore stores a fresh dump of the database on the backup target first, then loads the upload
 * into the live container; while a dump, a restore or an engine operation holds the database, it is refused at once,
 * in words.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class DatabaseRestoreUploadTest extends HohenheimTestBase {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final String BOUNDARY = "HohenheimRestoreTestBoundary";

    @Test
    void restoreWithoutFileRedirectsWithError() throws Exception {
        HttpResponse<String> response = adminPostForm("/databases/whatever/restore", "unused=1");
        assertThat(response.statusCode()).isEqualTo(302);
        // Unknown database: the redirect falls back to the admin list with an error.
        assertThat(response.headers().firstValue("Location").orElse(""))
            .startsWith("/admin/databases");
    }

    @Test
    void anUploadWhileTheDatabaseIsHeldIsRefusedAtOnceInWords() throws Exception {
        String name = "heldrestore";
        Row row = DumpTargetFixture.databaseRow(name, DatabaseModel.ENGINE_POSTGRES);
        int recordId = row.get(DatabaseModel.ID);
        DumpTargetFixture.Scripted dumps = DumpTargetFixture.scriptedDumps(new AtomicInteger());
        String dump = "CREATE TABLE things (x integer);\n";
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Integer instanceId = null;
        try (DumpTargetFixture target = DumpTargetFixture.install("held-restore-target");
             Racers racers = Racers.named("a running dump, then an upload")) {
            // 1. A dump of the database runs (parked inside its content lock): the upload is refused at once, by
            //    name, and stores nothing; it never queues behind the dump on this request thread.
            dumps.parkDumps(reached, release);
            Racers.Running<Object> running = racers.start("the dump", () -> DatabaseBackups.backUp(dumps, name,
                InstanceOperationLock.Contention.QUEUE));
            assertThat(reached.await(30, TimeUnit.SECONDS)).as("step 1 anchor: the dump holds the lock").isTrue();
            long asked = Now.millis();
            HttpResponse<String> busy = postMultipartFile("/databases/" + name + "/restore", "dump", "up.sql", dump);
            assertThat(Now.millis() - asked).as("step 1: refused at once, not after a wait").isLessThan(10_000L);
            assertThat(busy.statusCode()).isEqualTo(302);
            assertThat(popFlash(busy)).as("step 1: the refusal is toasted in words").isNotNull()
                .extracting(flash -> flash.message().key()).isEqualTo("database_busy");
            release.countDown();
            assertThat(running.await(Duration.ofSeconds(30)).failure()).as("step 1: the dump itself finished")
                .isNull();
            assertThat(DatabaseBackups.stored(recordId)).as("step 1: only the running dump was stored").hasSize(1);

            // 2. An operation holds the engine instance's claim: the pre-restore dump's gate refuses (a Violations),
            //    and the operator reads it as a toast instead of a failed request.
            String handle = EngineHandles.plant(recordId, name, "postgres", InstanceModel.STATUS_RUNNING);
            assertThat(handle).isNotBlank();
            int engineInstance = DatabaseInstances.owned(recordId).get(InstanceModel.ID);
            instanceId = engineInstance;
            CountDownLatch claimed = new CountDownLatch(1);
            CountDownLatch unclaim = new CountDownLatch(1);
            Racers.Running<Object> operation = racers.start("the operation", () -> InstanceOperationLock.production()
                .exclusive(engineInstance, InstanceOperationLock.Contention.QUEUE, () -> {
                    claimed.countDown();
                    try {
                        unclaim.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            assertThat(claimed.await(30, TimeUnit.SECONDS)).as("step 2 anchor: the claim is held").isTrue();
            HttpResponse<String> gated = postMultipartFile("/databases/" + name + "/restore", "dump", "up.sql", dump);
            unclaim.countDown();
            assertThat(gated.statusCode()).as("step 2: an ordinary answer, not a failed request").isEqualTo(302);
            assertThat(popFlash(gated)).as("step 2: the gate's refusal is toasted").isNotNull()
                .extracting(flash -> flash.message().key()).isEqualTo("instance_operation_in_progress");
            assertThat(operation.await(Duration.ofSeconds(30)).failure()).isNull();
            assertThat(DatabaseBackups.stored(recordId)).as("step 2: no dump was stored").hasSize(1);
        } finally {
            release.countDown();
            if (instanceId != null) {
                EngineHandles.unplant(recordId, instanceId);
            }
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(recordId)).delete();
        }
    }
    @Test
    void uploadedDumpRestoresIntoTheLiveDatabase() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, POSTGRES_IMAGE);

        String name = "restoreui" + System.nanoTime();
        DatabaseService databases = new DatabaseService();
        DatabaseModel model = Models.get(DatabaseModel.class);
        PrivateNetns netns = PrivateNetns.installEnforcing();
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: a record-backed database refuses without an enforceable policy");
        try (DumpTargetFixture target = DumpTargetFixture.install("restore-upload-target")) {
            // The record AND its owned engine instance in one call; the restore handler
            // resolves the container from the record's owned instance.
            databases.create(name, ManagedDatabase.Engine.POSTGRES, POSTGRES_IMAGE,
                "appuser", "secret123", "appdb", true);
            Row row = model.findByName(name);
            String container = DatabaseInstances.handleOf(row.get(DatabaseModel.ID));
            assertThat(container).as("the database owns an engine instance").isNotNull();

            String dump = "CREATE TABLE things (x integer);\nINSERT INTO things VALUES (7);\n";
            HttpResponse<String> response = postMultipartFile(
                "/databases/" + name + "/restore", "dump", name + ".sql", dump);
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(landingOf(response))
                .describedAs("the confirmation rides the session flash, so the URL stays clean")
                .doesNotContain("restored");
            var restoredFlash = popFlash(response);
            assertThat(restoredFlash).describedAs("the restore stashes a confirmation").isNotNull();
            assertThat(restoredFlash.message().key()).isEqualTo("restored");

            DockerClient.ExecResult result = docker.exec(container,
                List.of("psql", "-U", "appuser", "-d", "appdb", "-tAc", "SELECT x FROM things"),
                List.of("PGPASSWORD=secret123"));
            assertThat(result.exitCode()).withFailMessage("psql failed: %s", result.stderr()).isZero();
            assertThat(result.stdout().trim()).isEqualTo("7");   // dump landed in the live database
            assertThat(DatabaseBackups.stored(row.get(DatabaseModel.ID)))
                .as("the upload stored a pre-restore dump first").hasSize(1);
        } finally {
            try {
                databases.destroy(name, true);
            } catch (IOException ignored) {
                // best effort
            }
            model.find().where(DatabaseModel.NAME.eq(name)).delete();
            PrivateNetns.uninstall(netns);
        }
    }

    private HttpResponse<String> postMultipartFile(String path, String field, String filename,
                                                   String content) throws Exception {
        String body = "--" + BOUNDARY + "\r\n"
            + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n"
            + content + "\r\n"
            + "--" + BOUNDARY + "--\r\n";
        return httpPost(path, body, sessionToken, csrfToken, "multipart/form-data; boundary=" + BOUNDARY);
    }
}
