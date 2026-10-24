package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupStatus;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.cms.DatabaseDumpParts;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseRestores;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.testsupport.thread.Racers;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.security.SecureTokens;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A managed-database dump goes through THE backup target: refused while none is set, stored encrypted and verified
 * there, recorded and listed, pruned to the retention, and refused for a restore when its artifact was tampered with
 * or is gone, or when it is of another kind than its database. The dump itself is scripted, so no engine is needed;
 * the live restore journey is {@code DatabaseDumpRestoreLiveTest}.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DatabaseDumpTargetTest {

    @BeforeAll
    static void boot() throws Exception {
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void aDumpLandsEncryptedOnTheTargetIsListedPrunedAndRefusedOnceTampered() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        DatabaseService service = DumpTargetFixture.scriptedDumps(counter);
        Row shop = DumpTargetFixture.databaseRow("dumpshop", DatabaseModel.ENGINE_POSTGRES);
        Row broken = DumpTargetFixture.databaseRow("brokendump", DatabaseModel.ENGINE_POSTGRES);
        Integer shopId = shop.get(DatabaseModel.ID);
        Integer previousRetention = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION);
        try {
            // 1. No target set: the dump is refused naming the setting, nothing is recorded, and Back up now says why.
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_TARGET, "");
            assertThatThrownBy(() -> DatabaseBackups.backUp(service, "dumpshop"))
                .as("step 1: no target, no dump").isInstanceOf(IOException.class)
                .hasMessageContaining(HohenheimSettings.Database.BACKUP_TARGET.getPath());
            assertThat(DatabaseBackups.targetRefusal()).as("step 1: the offer is dead with a reason").isNotNull();
            assertThat(Models.get(DatabaseDumpModel.class).find().count()).as("step 1: no row").isZero();

            try (DumpTargetFixture target = DumpTargetFixture.install("dump-target-test")) {
                assertThat(DatabaseBackups.targetRefusal()).as("step 2: a set target takes dumps").isNull();

                // 2. The dump lands on the target encrypted, verified by the target's own hash, nothing left staged.
                Row dump = DatabaseBackups.backUp(service, "dumpshop");
                assertThat((String) dump.get(DatabaseDumpModel.STATUS)).as("step 2: complete")
                    .isEqualTo(BackupStatus.COMPLETE.key());
                Path artifact = target.artifactOf(dump);
                byte[] stored = Files.readAllBytes(artifact);
                assertThat(new String(stored, 0, 4, StandardCharsets.US_ASCII)).as("step 2: the dump format's magic")
                    .isEqualTo("HDB1");
                assertThat(new String(stored, StandardCharsets.ISO_8859_1)).as("step 2: no plaintext on the target")
                    .doesNotContain("CREATE TABLE");
                assertThat(SecureTokens.sha256Hex(artifact)).as("step 2: the row holds the target's hash")
                    .isEqualTo(dump.get(DatabaseDumpModel.SHA256));
                assertThat((Long) dump.get(DatabaseDumpModel.SIZE_BYTES)).isEqualTo((long) stored.length);
                assertThat((String) dump.get(DatabaseDumpModel.ENGINE)).isEqualTo(DatabaseModel.ENGINE_POSTGRES);
                assertThat((String) dump.get(DatabaseDumpModel.DB_NAME)).isEqualTo("appdb");
                int dumpId = dump.get(DatabaseDumpModel.ID);

                // 3. It is listed for a restore, and its download decrypts to the very dump.
                assertThat(DatabaseBackups.stored(shopId)).extracting(DatabaseBackups.Stored::id)
                    .as("step 3: listed").containsExactly((Integer) dump.get(DatabaseDumpModel.ID));
                Path work = Files.createTempDirectory("dump-fetch");
                Path plain = DatabaseBackups.fetchVerified(dump, work);
                assertThat(Files.readString(plain)).as("step 3: the round trip is exact")
                    .isEqualTo(DumpTargetFixture.DUMP_SQL + "1);\n");

                // 4. A failed dump leaves a FAILED row with its reason and no artifact, and lists nothing.
                assertThat(DatabaseBackups.backUpOrAlert(service, "brokendump", InstanceOperationLock.Contention.QUEUE))
                    .as("step 4: the failure is named")
                    .contains("pg_dump exited 1");
                Row failed = Models.get(DatabaseDumpModel.class).find()
                    .where(DatabaseDumpModel.DATABASE_ID.eq(broken.get(DatabaseModel.ID))).first();
                assertThat((String) failed.get(DatabaseDumpModel.STATUS)).isEqualTo(BackupStatus.FAILED.key());
                assertThat((String) failed.get(DatabaseDumpModel.ERROR)).contains("pg_dump exited 1");
                assertThat(DatabaseBackups.stored(broken.get(DatabaseModel.ID))).as("step 4: never listed").isEmpty();

                // 5. A dump of another kind is refused before anything is dumped or overwritten.
                setEngine(dumpId, DatabaseModel.ENGINE_REDIS);
                long rowsBefore = Models.get(DatabaseDumpModel.class).find().count();
                Throwable mismatch = catchThrowable(() -> DatabaseRestores.restoreInPlace(service, dumpId));
                assertThat(mismatch).as("step 5: refused by the kind reason")
                    .isInstanceOfSatisfying(DomainRefusal.class, refusal ->
                        assertThat(refusal.is(HohenheimRefusalReason.DATABASE_DUMP_KIND_MISMATCH)).isTrue());
                assertThat(Models.get(DatabaseDumpModel.class).find().count()).as("step 5: no pre-restore dump either")
                    .isEqualTo(rowsBefore);
                setEngine(dumpId, DatabaseModel.ENGINE_POSTGRES);

                // 6. A tampered artifact is refused whole, before the pre-restore dump.
                byte[] tampered = Arrays.copyOf(stored, stored.length);
                tampered[tampered.length - 1] ^= 0x01;
                Files.write(artifact, tampered);
                assertThatThrownBy(() -> DatabaseBackups.fetchVerified(dump, work))
                    .as("step 6: the checksum refuses it").hasMessageContaining("checksum");
                assertThat(catchThrowable(() -> DatabaseRestores.restoreInPlace(service,
                        dump.get(DatabaseDumpModel.ID))))
                    .as("step 6: the restore refuses it as unusable").isInstanceOfSatisfying(DomainRefusal.class,
                        refusal -> assertThat(refusal.is(HohenheimRefusalReason.DATABASE_DUMP_UNUSABLE)).isTrue());
                assertThat(Models.get(DatabaseDumpModel.class).find().count()).isEqualTo(rowsBefore);

                // 7. A missing artifact is refused the same way, naming that it is missing.
                Files.delete(artifact);
                Throwable missing = catchThrowable(() -> DatabaseRestores.restoreToNew(service,
                    dump.get(DatabaseDumpModel.ID), "dumpshop-copy"));
                assertThat(missing).as("step 7: refused as unusable").isInstanceOfSatisfying(DomainRefusal.class,
                    refusal -> assertThat(refusal.is(HohenheimRefusalReason.DATABASE_DUMP_UNUSABLE)).isTrue());
                assertThat(Models.get(DatabaseModel.class).findByName("dumpshop-copy"))
                    .as("step 7: no database was created for it").isNull();

                // 8. Retention keeps the newest two complete dumps; an older one leaves the target with its row.
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_RETENTION, 2);
                Row second = DatabaseBackups.backUp(service, "dumpshop");
                Row third = DatabaseBackups.backUp(service, "dumpshop");
                assertThat(DatabaseBackups.stored(shopId)).extracting(DatabaseBackups.Stored::id)
                    .as("step 8: the newest two, newest first")
                    .containsExactly((Integer) third.get(DatabaseDumpModel.ID),
                        (Integer) second.get(DatabaseDumpModel.ID));
                assertThat(Models.get(DatabaseDumpModel.class).findById(dump.get(DatabaseDumpModel.ID)))
                    .as("step 8: the oldest row is gone").isNull();
                assertThat(DatabaseBackups.newest(shopId)).extracting(DatabaseBackups.Stored::id)
                    .isEqualTo(third.get(DatabaseDumpModel.ID));

                // 9. The database deleted: its dumps stay listed, and an in-place restore says the source is gone.
                Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(shopId)).delete();
                assertThat(DatabaseBackups.stored(shopId)).as("step 9: a dump outlives its database").hasSize(2);
                assertThat(catchThrowable(() -> DatabaseRestores.restoreInPlace(service,
                        third.get(DatabaseDumpModel.ID))))
                    .isInstanceOfSatisfying(DomainRefusal.class, refusal ->
                        assertThat(refusal.is(HohenheimRefusalReason.DATABASE_DUMP_SOURCE_GONE)).isTrue());
            }
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_RETENTION, previousRetention);
            Models.get(DatabaseDumpModel.class).find().delete();
            Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("dumpshop")).delete();
            Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("brokendump")).delete();
        }
    }

    @Test
    void anInPlaceRestoreDumpsFirstUnderTheContentLockAndAFailedLoadSaysWhatItLeft() throws Exception {
        DumpTargetFixture.Scripted service = DumpTargetFixture.scriptedDumps(new AtomicInteger());
        Row lockshop = DumpTargetFixture.databaseRow("lockshop", DatabaseModel.ENGINE_POSTGRES);
        DumpTargetFixture.databaseRow("halfwaypg", DatabaseModel.ENGINE_POSTGRES);
        Row mysql = DumpTargetFixture.databaseRow("halfwaymy", DatabaseModel.ENGINE_MYSQL);
        Integer previousRetention = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_RETENTION, 10);
        Path upload = Files.createTempFile("dump-upload", ".sql");
        try (DumpTargetFixture target = DumpTargetFixture.install("dump-restore-test")) {
            // 1. A dump runs inside the database's content lock (the scripted engine refuses to run outside it), in a
            //    staging area only its owner can enter.
            Row dump = DatabaseBackups.backUp(service, "lockshop");
            assertThat((String) dump.get(DatabaseDumpModel.STATUS)).as("step 1: dumped under the lock")
                .isEqualTo(BackupStatus.COMPLETE.key());
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(DatabaseBackups.stagingRoot())))
                .as("step 1: the staging area is its owner's alone").isEqualTo("rwx------");
            int dumpId = dump.get(DatabaseDumpModel.ID);

            // 2. In place: a fresh dump first, then the load, under the lock, from an owner-only file in an
            //    owner-only directory.
            Row before = DatabaseRestores.restoreInPlace(service, dumpId);
            assertThat((Integer) before.get(DatabaseDumpModel.ID)).as("step 2: the pre-restore dump is newer")
                .isGreaterThan(dumpId);
            assertThat((String) before.get(DatabaseDumpModel.STATUS)).isEqualTo(BackupStatus.COMPLETE.key());
            assertThat(service.loaded()).as("step 2: the stored dump was loaded")
                .containsExactly("lockshop: " + DumpTargetFixture.DUMP_SQL + "1);\n");
            assertThat(service.loadPermissions()).as("step 2: the plaintext is its owner's alone")
                .containsExactly("rw------- in rwx------");

            // 3. The Restore tab's upload is the same in-place restore: its own fresh dump first, then the load.
            Files.writeString(upload, "CREATE TABLE uploaded (x integer);\n");
            Row beforeUpload = DatabaseRestores.restoreUploadInPlace(service, "lockshop", upload);
            assertThat((Integer) beforeUpload.get(DatabaseDumpModel.ID)).as("step 3: the upload dumped first")
                .isGreaterThan((Integer) before.get(DatabaseDumpModel.ID));
            assertThat(service.loaded()).as("step 3: then loaded the upload")
                .endsWith("lockshop: CREATE TABLE uploaded (x integer);\n");

            // 3b. An upload that creates or connects to a database of its own (pg_dump --create, pg_dumpall) is
            //     refused by name before the pre-restore dump, and nothing is loaded.
            int dumpsBefore = DatabaseBackups.stored(lockshop.get(DatabaseModel.ID)).size();
            for (String header : List.of("CREATE DATABASE appdb WITH TEMPLATE = template0 ENCODING = 'UTF8';\n",
                    "SET default_transaction_read_only = off;\n\\connect template1\n")) {
                Files.writeString(upload, "--\n-- PostgreSQL database dump\n--\n" + header
                    + "CREATE TABLE whole (x integer);\n");
                assertThat(catchThrowable(() -> DatabaseRestores.restoreUploadInPlace(service, "lockshop", upload)))
                    .as("step 3b: refused as a whole-server dump").isInstanceOfSatisfying(DomainRefusal.class,
                        refusal -> {
                            assertThat(refusal.is(HohenheimRefusalReason.DATABASE_DUMP_WHOLE_SERVER)).isTrue();
                            assertThat(refusal.words().key()).isEqualTo("database_dump_whole_server_detail");
                        });
            }
            assertThat(DatabaseBackups.stored(lockshop.get(DatabaseModel.ID))).as("step 3b: no pre-restore dump")
                .hasSize(dumpsBefore);
            assertThat(service.loaded()).as("step 3b: nothing loaded").hasSize(2);

            // 3c. An ephemeral database whose engine restores by restart is refused before its pre-restore dump.
            Row ephemeral = DumpTargetFixture.databaseRow("ephemeralredis", DatabaseModel.ENGINE_REDIS);
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(ephemeral.get(DatabaseModel.ID)))
                .assign(DatabaseModel.EPHEMERAL, true).updateAll();
            assertThat(catchThrowable(() -> DatabaseRestores.restoreUploadInPlace(service, "ephemeralredis", upload)))
                .as("step 3c: refused as unsupported").isInstanceOf(UnsupportedOperationException.class);
            assertThat(DatabaseBackups.stored(ephemeral.get(DatabaseModel.ID))).as("step 3c: and nothing was dumped")
                .isEmpty();

            // 4. A PostgreSQL load runs as one transaction, so its failure says the database is as it was.
            assertThat(ManagedDatabase.Engine.POSTGRES.restoreFromStdinCommand("appuser", "appdb", 1, null).get(2))
                .as("step 4: one transaction").contains("--single-transaction");
            int pgDump = DatabaseBackups.backUp(service, "halfwaypg").get(DatabaseDumpModel.ID);
            assertThat(catchThrowable(() -> DatabaseRestores.restoreInPlace(service, pgDump)))
                .as("step 4: refused as rolled back").isInstanceOfSatisfying(DomainRefusal.class, refusal -> {
                    assertThat(refusal.is(HohenheimRefusalReason.DATABASE_RESTORE_FAILED)).isTrue();
                    assertThat(refusal.words().key()).isEqualTo("database_restore_failed_unchanged_detail");
                });

            // 5. A MySQL load commits as it goes: its failure says the database may be mixed and names the
            //    pre-restore dump to recover from.
            int myDump = DatabaseBackups.backUp(service, "halfwaymy").get(DatabaseDumpModel.ID);
            Throwable mixed = catchThrowable(() -> DatabaseRestores.restoreInPlace(service, myDump));
            int preRestore = DatabaseBackups.newest(mysql.get(DatabaseModel.ID)).id();
            assertThat(preRestore).as("step 5: the pre-restore dump was stored").isGreaterThan(myDump);
            assertThat(mixed).as("step 5: refused as possibly mixed").isInstanceOfSatisfying(DomainRefusal.class,
                refusal -> {
                    assertThat(refusal.is(HohenheimRefusalReason.DATABASE_RESTORE_FAILED)).isTrue();
                    assertThat(refusal.words().key()).isEqualTo("database_restore_failed_mixed_detail");
                    assertThat(refusal.words().args().get("dump")).as("step 5: naming the dump to recover from")
                        .isEqualTo(String.valueOf(preRestore));
                });
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_RETENTION, previousRetention);
            Files.deleteIfExists(upload);
            Models.get(DatabaseDumpModel.class).find().delete();
            Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.NAME.in(List.of("lockshop", "halfwaypg", "halfwaymy", "ephemeralredis")))
                .delete();
        }
    }

    @Test
    void aFailedRestoreIntoANewDatabaseRemovesWhatItCreatedOrSaysItCouldNot() throws Exception {
        DumpTargetFixture.Scripted service = DumpTargetFixture.scriptedDumps(new AtomicInteger());
        Row source = DumpTargetFixture.databaseRow("newsource", DatabaseModel.ENGINE_POSTGRES);
        try (DumpTargetFixture target = DumpTargetFixture.install("dump-new-test")) {
            int dumpId = DatabaseBackups.backUp(service, "newsource").get(DatabaseDumpModel.ID);

            // 1. A provision that fails after its record exists leaves no record holding the name.
            assertThat(catchThrowable(() -> DatabaseRestores.restoreToNew(service, dumpId, "unprovisionable-copy")))
                .as("step 1: the provision's failure is the answer").hasMessageContaining("provision failed");
            assertThat(Models.get(DatabaseModel.class).findByName("unprovisionable-copy"))
                .as("step 1: nothing blocks the name").isNull();

            // 2. A failed load whose cleanup fails too is refused naming both.
            Throwable stuck = catchThrowable(() -> DatabaseRestores.restoreToNew(service, dumpId, "stuck-copy"));
            assertThat(stuck).as("step 2: the cleanup's failure is surfaced").isInstanceOfSatisfying(
                DomainRefusal.class, refusal -> {
                    assertThat(refusal.is(HohenheimRefusalReason.DATABASE_RESTORE_FAILED)).isTrue();
                    assertThat(refusal.words().key()).isEqualTo("database_restore_new_cleanup_failed_detail");
                    assertThat(String.valueOf(refusal.words().args().get("cleanup")))
                        .contains("the daemon did not answer");
                    assertThat(String.valueOf(refusal.words().args().get("reason"))).contains("psql exited 3");
                });

            // 3. A name another record holds is refused by the create itself, and that record survives with what it
            //    holds: the cleanup acts on the record this restore created, never on the name.
            Row copy = DatabaseRestores.restoreToNew(service, dumpId, "fresh-copy");
            assertThat(service.loaded()).as("step 3 anchor: the first copy was loaded")
                .containsExactly("fresh-copy: " + DumpTargetFixture.DUMP_SQL + "1);\n");
            for (String taken : List.of("fresh-copy", "newsource")) {
                Throwable clash = catchThrowable(() -> DatabaseRestores.restoreToNew(service, dumpId, taken));
                assertThat(clash).as("step 3: a second restore into %s is refused by name", taken)
                    .isInstanceOfSatisfying(Violations.class, refused ->
                        assertThat(refused.all().get(0).message().key()).isEqualTo("database_name_taken"));
            }
            assertThat((Integer) Models.get(DatabaseModel.class).findByName("fresh-copy").get(DatabaseModel.ID))
                .as("step 3: the existing copy survives").isEqualTo(copy.get(DatabaseModel.ID));
            assertThat(service.loaded()).as("step 3: and keeps the rows it was given, nothing loaded over them")
                .hasSize(1);
            assertThat(Models.get(DatabaseModel.class).findByName("newsource")).as("step 3: the source survives")
                .isNotNull();
            assertThat(DatabaseBackups.stored(source.get(DatabaseModel.ID))).as("step 3: with its dump").hasSize(1);
        } finally {
            Models.get(DatabaseDumpModel.class).find().delete();
            Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.NAME.in(List.of("newsource", "unprovisionable-copy", "stuck-copy", "fresh-copy")))
                .delete();
        }
    }

    @Test
    void aRunningDumpRefusesEveryOtherDumpAndRestoreOfItsDatabaseAtOnce() throws Exception {
        DumpTargetFixture.Scripted service = DumpTargetFixture.scriptedDumps(new AtomicInteger());
        Row busy = DumpTargetFixture.databaseRow("busyshop", DatabaseModel.ENGINE_POSTGRES);
        Path upload = Files.createTempFile("busy-upload", ".sql");
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (DumpTargetFixture target = DumpTargetFixture.install("dump-busy-test")) {
            Row stored = DatabaseBackups.backUp(service, "busyshop");
            int dumpId = stored.get(DatabaseDumpModel.ID);
            // Its artifact gone: a restore that fetched before taking the lock would answer "unusable", not "busy".
            Files.delete(target.artifactOf(stored));
            Files.writeString(upload, "CREATE TABLE uploaded (x integer);\n");
            try (Racers racers = Racers.named("a running nightly dump, then every other caller")) {
                // 1. The nightly dump runs, parked inside the database's content lock; it alone may queue.
                service.parkDumps(reached, release);
                Racers.Running<Object> nightly = racers.start("the nightly dump",
                    () -> DatabaseBackups.backUp(service, "busyshop", InstanceOperationLock.Contention.QUEUE));
                assertThat(reached.await(30, TimeUnit.SECONDS)).as("step 1 anchor: the dump holds the lock").isTrue();

                // 2. The upload, Back up now (inline, before the pool), a stored dump's restore (before its fetch),
                //    another dump and a Delete are each refused at once by name; nothing is loaded or torn down.
                long asked = Now.millis();
                List<ThrowingCallable> callers = List.of(
                    () -> DatabaseRestores.restoreUploadInPlace(service, "busyshop", upload),
                    () -> DatabaseBackups.backUpInBackground(busy),
                    () -> DatabaseRestores.restoreInPlace(service, dumpId),
                    () -> DatabaseBackups.backUp(service, "busyshop"),
                    () -> service.destroy("busyshop", true));
                for (ThrowingCallable caller : callers) {
                    assertThat(catchThrowable(caller)).as("step 2: refused as busy")
                        .isInstanceOfSatisfying(DomainRefusal.class, refusal ->
                            assertThat(refusal.is(HohenheimRefusalReason.DATABASE_BUSY)).isTrue());
                }
                assertThat(Now.millis() - asked).as("step 2: at once, never after a wait").isLessThan(10_000L);
                assertThat(service.loaded()).as("step 2: nothing was loaded").isEmpty();
                assertThat(Models.get(DatabaseModel.class).findByName("busyshop"))
                    .as("step 2: and the refused Delete kept the record").isNotNull();

                // 3. The running dump finishes as if nobody had asked.
                release.countDown();
                assertThat(nightly.await(Duration.ofSeconds(30)).failure()).as("step 3: the nightly dump stored")
                    .isNull();
            }
            assertThat(DatabaseBackups.stored(busy.get(DatabaseModel.ID))).as("step 3: only the two real dumps")
                .hasSize(2);
        } finally {
            release.countDown();
            Files.deleteIfExists(upload);
            Models.get(DatabaseDumpModel.class).find().delete();
            Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("busyshop")).delete();
        }
    }

    @Test
    void noPlaintextOutlivesAKilledDumpAndAnUnsettledOrFailedRowKeepsWhatItsArtifactNeeds() throws Exception {
        DumpTargetFixture.Scripted service = DumpTargetFixture.scriptedDumps(new AtomicInteger());
        Row shop = DumpTargetFixture.databaseRow("settleshop", DatabaseModel.ENGINE_POSTGRES);
        String previousPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_PATH);
        Path scratch = Files.createTempDirectory("dump-staging-test");
        try (DumpTargetFixture target = DumpTargetFixture.install("dump-settle-test")) {
            // 1. The boot sweeps what a killed controller left staged, plaintext included, never what this one stages.
            Path stale = Files.createDirectories(DatabaseBackups.stagingRoot().resolve("dump-killed"));
            Files.writeString(stale.resolve("20260101T030000Z.sql"), DumpTargetFixture.DUMP_SQL + "0);\n");
            Files.setLastModifiedTime(stale, FileTime.from(Instant.EPOCH));
            Path live = Files.createDirectories(DatabaseBackups.stagingRoot().resolve("dump-live"));
            DatabaseBackups.recoverInterrupted();
            assertThat(stale).as("step 1: the dead dump's plaintext is gone").doesNotExist();
            assertThat(live).as("step 1: this process's staging stays").exists();
            Files.delete(live);

            // 2. A staging area that cannot be made fails the dump with its row FAILED, never left uploading.
            Path blocker = Files.writeString(scratch.resolve("not-a-directory"), "x");
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, blocker.resolve("backups").toString());
            assertThatThrownBy(() -> DatabaseBackups.backUp(service, "settleshop")).as("step 2: the dump fails")
                .isInstanceOf(IOException.class);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, previousPath);
            Row failed = Models.get(DatabaseDumpModel.class).find()
                .where(DatabaseDumpModel.DATABASE_ID.eq(shop.get(DatabaseModel.ID))).first();
            assertThat((String) failed.get(DatabaseDumpModel.STATUS)).as("step 2: the row is settled FAILED")
                .isEqualTo(BackupStatus.FAILED.key());

            // 2b. A staging root its owner cannot keep to itself (here a symbolic link) refuses the dump naming the
            //     directory and why, with the row FAILED saying so; nothing follows the link.
            Path linkedRoot = Files.createDirectories(scratch.resolve("linked"));
            Path elsewhere = Files.createDirectories(scratch.resolve("elsewhere"));
            Files.setPosixFilePermissions(elsewhere, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.createSymbolicLink(linkedRoot.resolve(DatabaseBackups.STAGING_SUBDIRECTORY), elsewhere);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, linkedRoot.toString());
            Throwable unsafe = catchThrowable(() -> DatabaseBackups.backUp(service, "settleshop"));
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, previousPath);
            String stagingPath = linkedRoot.resolve(DatabaseBackups.STAGING_SUBDIRECTORY).toString();
            assertThat(unsafe).as("step 2b: refused by name").isInstanceOfSatisfying(DomainRefusal.class, refusal -> {
                assertThat(refusal.is(HohenheimRefusalReason.DATABASE_STAGING_UNSAFE)).isTrue();
                assertThat(refusal.words().key()).isEqualTo("database_staging_unsafe_detail");
                assertThat(String.valueOf(refusal.words().args().get("path"))).as("step 2b: naming the directory")
                    .isEqualTo(stagingPath);
                assertThat(String.valueOf(refusal.words().args().get("reason"))).as("step 2b: and why")
                    .contains("symbolic link");
            });
            Row unsafeRow = Models.get(DatabaseDumpModel.class).find()
                .where(DatabaseDumpModel.DATABASE_ID.eq(shop.get(DatabaseModel.ID)))
                .where(DatabaseDumpModel.ID.ne(failed.get(DatabaseDumpModel.ID))).first();
            assertThat((String) unsafeRow.get(DatabaseDumpModel.STATUS)).isEqualTo(BackupStatus.FAILED.key());
            assertThat((String) unsafeRow.get(DatabaseDumpModel.ERROR)).as("step 2b: the row names it too")
                .contains(stagingPath);
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(elsewhere)))
                .as("step 2b: the link's target was never narrowed").isEqualTo("rwxr-xr-x");
            try (Stream<Path> listed = Files.list(elsewhere)) {
                assertThat(listed.toList()).as("step 2b: and nothing was staged through it").isEmpty();
            }
            Models.get(DatabaseDumpModel.class).find()
                .where(DatabaseDumpModel.ID.eq(unsafeRow.get(DatabaseDumpModel.ID))).delete();

            // 3. A row still uploading offers no delete, which would orphan the artifact its upload commits.
            Row uploading = Models.get(DatabaseDumpModel.class).createEmptyRow();
            uploading.set(DatabaseDumpModel.DATABASE_ID, shop.get(DatabaseModel.ID));
            uploading.set(DatabaseDumpModel.DATABASE_NAME, "settleshop");
            uploading.set(DatabaseDumpModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
            uploading.set(DatabaseDumpModel.TARGET_ID, failed.get(DatabaseDumpModel.TARGET_ID));
            uploading.set(DatabaseDumpModel.STATUS, BackupStatus.UPLOADING.key());
            uploading.set(DatabaseDumpModel.REMOTE_KEY, "in-flight/dump.sql.hdb");
            Models.get(DatabaseDumpModel.class).save(uploading);
            assertThat(OperationPipeline.offer(DatabaseDumpParts.DELETE, TenantConduits.operator(), uploading))
                .as("step 3: no delete while uploading").isInstanceOf(OperationPipeline.Offer.Hidden.class);
            assertThat(OperationPipeline.offer(DatabaseDumpParts.DELETE, TenantConduits.operator(), failed))
                .as("step 3: a failed row's delete is offered").isNotInstanceOf(OperationPipeline.Offer.Hidden.class);
            Models.get(DatabaseDumpModel.class).find()
                .where(DatabaseDumpModel.ID.eq(uploading.get(DatabaseDumpModel.ID))).delete();

            // 4. A FAILED dump holds its target, so its own delete can still reach whatever artifact survived.
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_TARGET, "");
            int targetId = failed.get(DatabaseDumpModel.TARGET_ID);
            Throwable held = catchThrowable(() -> Models.get(BackupTargetModel.class).delete((Object) targetId));
            assertThat(held).as("step 4: the target is refused while the failed dump names it")
                .isInstanceOfSatisfying(Violations.class, refused ->
                    assertThat(refused.all().get(0).message().key()).isEqualTo("backup_target_in_use"));
            assertThat((Integer) Models.get(DatabaseDumpModel.class).findById(failed.get(DatabaseDumpModel.ID))
                .get(DatabaseDumpModel.TARGET_ID)).as("step 4: the failed row keeps its target").isEqualTo(targetId);
            DatabaseBackups.delete(failed);
            Models.get(BackupTargetModel.class).delete((Object) targetId);
            assertThat(Models.get(BackupTargetModel.class).findById(targetId)).as("step 4: then the target goes")
                .isNull();
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_PATH, previousPath);
            FileTrees.deleteQuietly(scratch);
            Models.get(DatabaseDumpModel.class).find().delete();
            Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("settleshop")).delete();
        }
    }

    private static void setEngine(int dumpId, String engine) {
        Models.get(DatabaseDumpModel.class).find().where(DatabaseDumpModel.ID.eq(dumpId))
            .assign(DatabaseDumpModel.ENGINE, engine).updateAll();
    }
}
