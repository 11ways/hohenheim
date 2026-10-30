package be.elevenways.hohenheim.test.database;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseInstances;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.protoblast.testsupport.thread.Racers;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A move onto a shared engine is CLAIMED atomically: the eligibility check and the
 * provisioning stamp used to be two steps, so a double-submitted action started two moves of
 * one record. The claim is now a conditional update on "still active and dedicated", taken
 * synchronously, so the second submit is refused by name before any daemon work. No daemon
 * is contacted by the first test: the refusal happens before one would be. The second runs a move and a password
 * change of one dedicated database against a fake daemon, each racing the other.
 */
class DatabaseMoveClaimTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final String NAME = "move-claim";
    private static final String SERIAL = "move-serial";
    private static final String LANDING = "move-landing";
    private static final String ROOT_PASSWORD = "Move.root-password-00004";
    private static final String OLD_PASSWORD = "Move.old-password-000001";
    private static final String NEW_PASSWORD = "Move.new-password-000002";
    private static final String NEWER_PASSWORD = "Move.newer-password-0003";


    @AfterAll
    static void tearDown() throws Exception {
    }

    @Test
    void aMoveThatAnotherOperationHoldsIsRefusedAndLeavesTheRecordAlone() {
        DatabaseService service = new DatabaseService();
        service.insertRecord(NAME, ManagedDatabase.Engine.POSTGRES, null, "app", "pw", "app", false,
            ServerService.LOCAL_HOST_NAME, ResourceLimits.none(), DatabaseModel.STATUS_ACTIVE,
            DatabaseModel.PLACEMENT_DEDICATED, null);

        // 1. Another move (or any operation) holds the record: it reads provisioning.
        Row held = Models.get(DatabaseModel.class).findByName(NAME);
        held.set(DatabaseModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        Models.get(DatabaseModel.class).save(held);

        // 2. The panel/API lane refuses SYNCHRONOUSLY, naming why, and queues nothing.
        Violations refused = catchThrowableOfType(
            () -> service.moveToSharedEngineInBackground(NAME), Violations.class);
        assertThat((Throwable) refused).as("step 2: the second submit is refused").isNotNull();
        assertThat(refused.all().get(0).message().key())
            .as("step 2: because the record is not active").isEqualTo("database_not_active");

        // 3. The blocking lane refuses the same way, and neither touched the record.
        assertThatThrownBy(() -> service.moveToSharedEngine(NAME))
            .as("step 3: the blocking lane refuses too")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("database_not_active");
        Row after = Models.get(DatabaseModel.class).findByName(NAME);
        assertThat((String) after.get(DatabaseModel.STATUS))
            .as("step 3: the holder's status is untouched").isEqualTo(DatabaseModel.STATUS_PROVISIONING);
        assertThat((String) after.get(DatabaseModel.PLACEMENT))
            .as("step 3: and it is still dedicated").isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);

        Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq(NAME)).delete();
    }

    /**
     * A move and a password change of one dedicated database run one after the other, whichever came first: each
     * holds the engine instance's claim while it works with the password. A move asked for during a change waits
     * for it and then dumps, fingerprints and would create the shared user with the NEW password, never the one it
     * read when it claimed the record; a change asked for during a move waits until the move ended.
     */
    @Test
    void aMoveAndAPasswordChangeOfOneDedicatedDatabaseRunOneAfterTheOther() throws Exception {
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        DockerClient.overrideLocalTransportForTest(() -> daemon);
        try {
            DatabaseService service = new DatabaseService();
            service.insertRecord(SERIAL, ManagedDatabase.Engine.POSTGRES, null, "app", OLD_PASSWORD, "appdb", false,
                ServerService.LOCAL_HOST_NAME, ResourceLimits.none(), DatabaseModel.STATUS_ACTIVE,
                DatabaseModel.PLACEMENT_DEDICATED, null);
            DatabaseModel databases = Models.get(DatabaseModel.class);
            int recordId = databases.findByName(SERIAL).get(DatabaseModel.ID);
            String handle = EngineHandles.plant(recordId, SERIAL, "postgres", InstanceModel.STATUS_RUNNING);
            int engineInstance = DatabaseInstances.owned(recordId).get(InstanceModel.ID);
            daemon.seedRunning(handle);
            InstanceOperationLock claims = InstanceOperationLock.production();
            ManagedDatabase.Engine postgres = ManagedDatabase.Engine.POSTGRES;

            // 1. A change sits in the engine; a move asked for now waits for its claim and runs nothing.
            CountDownLatch changeInEngine = new CountDownLatch(1);
            CountDownLatch releaseChange = new CountDownLatch(1);
            daemon.duringNextExec(() -> park(changeInEngine, releaseChange));
            try (Racers racers = Racers.named("a password change, then a move")) {
                Racers.Running<Object> changing = racers.start("the change", () -> {
                    service.changePassword(recordId, NEW_PASSWORD);
                    return null;
                });
                assertThat(changeInEngine.await(30, TimeUnit.SECONDS))
                    .as("step 1 anchor: the change reached the engine").isTrue();
                Racers.Running<Object> moving = racers.start("the move", () -> {
                    service.moveToSharedEngine(SERIAL);
                    return null;
                });
                Poll.until("step 1: the move queued on the claim, or ended", Duration.ofSeconds(10),
                    () -> claims.isQueued(engineInstance, moving.thread()) || !moving.isRunning());
                assertThat(daemon.execsOn(handle)).as("step 1: the move ran nothing in the engine").hasSize(1);
                assertThat(claims.isQueued(engineInstance, moving.thread()))
                    .as("step 1: it waits for the change's claim").isTrue();

                // 2. The change ends; the move dumps and fingerprints with the password the change stored. Its
                //    fingerprint is refused, which ends the move there with the record back on its own engine.
                daemon.duringNextExec(() -> daemon.refuseNextExec(1, "fingerprint refused"));
                releaseChange.countDown();
                assertThat(changing.await(Duration.ofSeconds(30)).failure()).as("step 2: the change succeeded")
                    .isNull();
                assertThat(moving.await(Duration.ofSeconds(30)).failure())
                    .as("step 2: the move ended at its refused fingerprint").isInstanceOf(IOException.class);
            }
            List<FakeDockerDaemon.Exec> execs = daemon.execsOn(handle);
            assertThat(execs).as("step 2: the change, the dump, the fingerprint").hasSize(3);
            assertThat(execs.get(0).env()).as("step 2: the change first")
                .isEqualTo(postgres.rotateEnv(OLD_PASSWORD, NEW_PASSWORD));
            assertThat(execs.get(1).env()).as("step 2: the dump authenticated with the new password")
                .containsExactly("PGPASSWORD=" + NEW_PASSWORD);
            assertThat(execs.get(2).command()).as("step 2: the fingerprint")
                .isEqualTo(postgres.fingerprintCommand("app", "appdb"));
            assertThat(execs.get(2).env()).as("step 2: with the new password, not the one the move claimed with")
                .isEqualTo(postgres.logicalEnv(NEW_PASSWORD, null));
            Row afterMove = databases.findById(recordId);
            assertThat((String) afterMove.get(DatabaseModel.DB_PASSWORD)).as("step 2: the record holds the new one")
                .isEqualTo(NEW_PASSWORD);
            assertThat((String) afterMove.get(DatabaseModel.PLACEMENT)).as("step 2: and is still dedicated")
                .isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);
            assertThat((String) afterMove.get(DatabaseModel.STATUS)).as("step 2: and active")
                .isEqualTo(DatabaseModel.STATUS_ACTIVE);

            // 3. A move sits in its fingerprint, past the dump; a change asked for now waits for the move's claim.
            CountDownLatch moveInEngine = new CountDownLatch(1);
            CountDownLatch releaseMove = new CountDownLatch(1);
            daemon.duringNextExec(() -> {
                daemon.refuseNextExec(1, "fingerprint refused");
                daemon.duringNextExec(() -> park(moveInEngine, releaseMove));
            });
            try (Racers racers = Racers.named("a move, then a password change")) {
                Racers.Running<Object> moving = racers.start("the move", () -> {
                    service.moveToSharedEngine(SERIAL);
                    return null;
                });
                assertThat(moveInEngine.await(30, TimeUnit.SECONDS))
                    .as("step 3 anchor: the move reached its fingerprint").isTrue();
                Racers.Running<Object> changing = racers.start("the change", () -> {
                    service.changePassword(recordId, NEWER_PASSWORD);
                    return null;
                });
                Poll.until("step 3: the change queued on the claim, or ended", Duration.ofSeconds(10),
                    () -> claims.isQueued(engineInstance, changing.thread()) || !changing.isRunning());
                assertThat(daemon.execsOn(handle)).as("step 3: the change ran nothing in the engine").hasSize(5);
                assertThat(claims.isQueued(engineInstance, changing.thread()))
                    .as("step 3: it waits for the move's claim").isTrue();

                // 4. The move ends (its fingerprint refused); then the change rotates the engine it still has.
                releaseMove.countDown();
                assertThat(moving.await(Duration.ofSeconds(30)).failure())
                    .as("step 4: the move ended at its refused fingerprint").isInstanceOf(IOException.class);
                assertThat(changing.await(Duration.ofSeconds(30)).failure()).as("step 4: then the change succeeded")
                    .isNull();
            }
            List<FakeDockerDaemon.Exec> serialized = daemon.execsOn(handle);
            assertThat(serialized).as("step 4: the move's dump and fingerprint, then the change").hasSize(6);
            assertThat(serialized.get(5).command()).as("step 4: the change came last")
                .isEqualTo(postgres.rotateOwnCommand("app"));
            assertThat(serialized.get(5).env()).as("step 4: from the new password to the newer one")
                .isEqualTo(postgres.rotateEnv(NEW_PASSWORD, NEWER_PASSWORD));
            assertThat((String) databases.findById(recordId).get(DatabaseModel.DB_PASSWORD))
                .as("step 4: the record names the engine's last password").isEqualTo(NEWER_PASSWORD);
        } finally {
            DockerClient.overrideLocalTransportForTest(null);
            daemon.close();
        }
    }

    /**
     * A move that fails after it created the logical database on the shared engine drops that database again, so a
     * retry is not refused by what the failed load left; a logical database that already existed there is never
     * dropped. The fake engine cannot take a restore's stdin, so each move fails in its load, past the create.
     */
    @Test
    void aFailedMoveDropsTheLogicalDatabaseItCreatedAndNeverOneThatExisted() throws Exception {
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        DockerClient.overrideLocalTransportForTest(() -> daemon);
        try {
            DatabaseService service = new DatabaseService();
            service.insertRecord(LANDING, ManagedDatabase.Engine.POSTGRES, null, "app", OLD_PASSWORD, "landdb", false,
                ServerService.LOCAL_HOST_NAME, ResourceLimits.none(), DatabaseModel.STATUS_ACTIVE,
                DatabaseModel.PLACEMENT_DEDICATED, null);
            DatabaseModel databases = Models.get(DatabaseModel.class);
            int recordId = databases.findByName(LANDING).get(DatabaseModel.ID);
            daemon.seedRunning(EngineHandles.plant(recordId, LANDING, "postgres", InstanceModel.STATUS_RUNNING));
            DatabaseEngineModel engines = Models.get(DatabaseEngineModel.class);
            Row engine = engines.createEmptyRow();
            engine.set(DatabaseEngineModel.NAME, LANDING + "-engine");
            engine.set(DatabaseEngineModel.ENGINE, "postgres");
            engine.set(DatabaseEngineModel.ROOT_USER, "root");
            engine.set(DatabaseEngineModel.ROOT_PASSWORD, ROOT_PASSWORD);
            engine.set(DatabaseEngineModel.SERVER_ID, ServerModel.localServerId());
            engine.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
            engines.save(engine);
            String shared = EngineHandles.plantEngine(engine.get(DatabaseEngineModel.ID), LANDING, "postgres",
                InstanceModel.STATUS_RUNNING);
            daemon.seedRunning(shared);
            ManagedDatabase.Engine postgres = ManagedDatabase.Engine.POSTGRES;
            List<String> objects = postgres.logicalObjectsCommand("root", "landdb");
            List<String> drop = postgres.dropLogicalCommand("root", "landdb", "app", true);

            // 1. The dump, the fingerprint, then the shared engine's probe answers that landdb is absent: the move
            //    creates it, its load fails, and it drops the database it created, as the engine's root.
            daemon.duringNextExec(() -> daemon.duringNextExec(() -> daemon.answerNextExec("absent")));
            assertThatThrownBy(() -> service.moveToSharedEngine(LANDING)).as("step 1: the move failed in its load")
                .isInstanceOf(IOException.class);
            List<FakeDockerDaemon.Exec> first = daemon.execsOn(shared);
            assertThat(first).extracting(FakeDockerDaemon.Exec::command)
                .as("step 1: the probe, the create, and the drop of what it created last")
                .startsWith(objects, postgres.createLogicalCommand("root", "landdb", "app"))
                .endsWith(drop);
            assertThat(first.get(first.size() - 1).env()).as("step 1: the drop runs as the engine's root")
                .isEqualTo(postgres.logicalEnv(ROOT_PASSWORD, null));
            Row afterFailure = databases.findById(recordId);
            assertThat((String) afterFailure.get(DatabaseModel.PLACEMENT)).as("step 1: the record stays dedicated")
                .isEqualTo(DatabaseModel.PLACEMENT_DEDICATED);
            assertThat((String) afterFailure.get(DatabaseModel.STATUS)).as("step 1: and active")
                .isEqualTo(DatabaseModel.STATUS_ACTIVE);

            // 2. NEGATIVE CONTROL: a landdb that already existed (empty) when the move probed is never dropped.
            daemon.duringNextExec(() -> daemon.duringNextExec(() -> daemon.answerNextExec("0")));
            assertThatThrownBy(() -> service.moveToSharedEngine(LANDING)).as("step 2: the retry failed in its load")
                .isInstanceOf(IOException.class);
            List<FakeDockerDaemon.Exec> all = daemon.execsOn(shared);
            List<FakeDockerDaemon.Exec> second = all.subList(first.size(), all.size());
            assertThat(second).extracting(FakeDockerDaemon.Exec::command)
                .as("step 2: the retry probed and created, and dropped nothing").startsWith(objects)
                .doesNotContain(drop);
        } finally {
            DockerClient.overrideLocalTransportForTest(null);
            daemon.close();
        }
    }

    /** Signals {@code reached}, then holds the calling engine command until {@code release} opens. */
    private static void park(CountDownLatch reached, CountDownLatch release) {
        reached.countDown();
        try {
            release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
