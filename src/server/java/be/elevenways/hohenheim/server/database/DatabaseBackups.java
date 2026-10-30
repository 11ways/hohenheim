package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BackupStatus;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.FirstPerOwner;
import be.elevenways.hohenheim.model.Retention;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.backup.BackupCipher;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.backup.UploadLedger;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.hohenheim.server.util.UtcStamp;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.io.DurableFiles;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The stored dumps of managed databases: every dump (the nightly task, "Back up now", an in-place restore's
 * pre-restore backup) is encrypted with the field-encryption keyring and stored on THE backup target the
 * {@code database.backup_target} setting names, and recorded as a {@link DatabaseDumpModel} row a restore lists.
 *
 * AIDEV-NOTE: the target is the ONE path a dump takes. The local directory that used to be the record is now only
 * the staging area: owner-only, each dump's or restore's directory deleted in a finally block, and swept at boot
 * ({@link #recoverInterrupted}) of whatever a killed controller left there; a filesystem target is how a dump stays
 * on this machine. A row goes
 * COMPLETE only after the target re-hashed the committed artifact and the hash matched the uploaded bytes, the
 * InstanceBackups rule, and a restore re-hashes what it downloads against that row before it decrypts.
 *
 * AIDEV-NOTE: dumps written by the directory store before this ledger existed ({@code <backup_path>/<name>/*.sql})
 * are left where they are, unencrypted and unlisted: nothing here reads, prunes or deletes them. They stay restorable
 * through the Restore tab's upload, and the operator removes them by hand once the target holds enough dumps.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class DatabaseBackups {

    /** The four magic bytes every encrypted dump starts with ({@link BackupCipher}). */
    static final byte[] MAGIC = {'H', 'D', 'B', '1'};

    /** The suffix of an encrypted dump's key, after the engine's own extension. */
    static final String ENCRYPTED_SUFFIX = ".hdb";

    /** Subdirectory of the backup path dumps are staged in before upload, never kept. */
    public static final String STAGING_SUBDIRECTORY = "database-staging";

    /** The permissions of the staging root: plaintext dumps pass through it. */
    private static final Set<PosixFilePermission> OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------");

    private static final Retention.InstanceCaptures RETENTION = new Retention.InstanceCaptures(
        DatabaseDumpModel.class, DatabaseDumpModel.ID, DatabaseDumpModel.DATABASE_ID, DatabaseDumpModel.STATUS,
        BackupStatus.COMPLETE.key(), HohenheimSettings.Database.BACKUP_RETENTION, "DUMP");

    private static final UploadLedger LEDGER = new UploadLedger(DatabaseDumpModel.class, DatabaseDumpModel.ID,
        DatabaseDumpModel.TARGET_ID,
        DatabaseDumpModel.STATUS, DatabaseDumpModel.REMOTE_KEY, DatabaseDumpModel.ERROR,
        DatabaseDumpModel.CREATED_AT, DatabaseDumpModel.UPDATED_AT, "DUMP");

    /**
     * One complete dump on the target.
     *
     * @param id    its ledger row
     * @param at    when it was taken
     * @param bytes the encrypted artifact's size
     */
    public record Stored(int id, @NonNull Instant at, long bytes) {

        static @NonNull Stored of(@NonNull Row dump) {
            Long size = dump.get(DatabaseDumpModel.SIZE_BYTES);
            Instant at = dump.get(DatabaseDumpModel.CREATED_AT);
            return new Stored(dump.get(DatabaseDumpModel.ID), at != null ? at : Instant.EPOCH,
                size != null ? size : 0L);
        }
    }

    /** The resolved destination of the dumps: its record and its live target. */
    private record Destination(int targetId, @NonNull String name, @NonNull BackupTarget target) {}

    private DatabaseBackups() {
    }

    /** @return the backup target name the setting holds, null when none is set */
    public static @Nullable String configuredTargetName() {
        String name = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_TARGET);
        return name == null || name.isBlank() ? null : name.trim();
    }

    /** @return why no dump can be stored now (no target set, or the set one is gone), null while one can */
    public static @Nullable Microcopy targetRefusal() {
        String name = configuredTargetName();
        if (name == null) {
            return HohenheimMicrocopy.DATABASE.of("back_up_no_target");
        }
        return targetRow(name) == null
            ? HohenheimMicrocopy.DATABASE.of("back_up_target_missing").withArg("target", name) : null;
    }

    static @Nullable Row targetRow(@NonNull String name) {
        return Models.get(BackupTargetModel.class).find().where(BackupTargetModel.NAME.eq(name)).first();
    }

    /** @throws IOException naming the setting when no target is set or the named one does not exist */
    private static @NonNull Destination requireDestination() throws IOException {
        String name = configuredTargetName();
        if (name == null) {
            throw new IOException("No backup target is set for managed-database dumps: set "
                + HohenheimSettings.Database.BACKUP_TARGET.getPath() + " to the name of a backup target (a filesystem"
                + " target keeps the dumps on this machine)");
        }
        Row row = targetRow(name);
        if (row == null) {
            throw new IOException("Setting " + HohenheimSettings.Database.BACKUP_TARGET.getPath()
                + " names backup target '" + name + "', which does not exist");
        }
        return new Destination(row.get(BackupTargetModel.ID), name, BackupTargetKinds.targetOf(row));
    }

    /**
     * Dumps one database, encrypts the dump, stores it on the target and verifies it there, then prunes the
     * database's older dumps to the retention setting, all holding the database's {@link DatabaseContentLock}; refused
     * at once while another dump or restore of it runs.
     *
     * @return the COMPLETE ledger row
     * @throws IOException when the target is unset or unhealthy, or the staging, the dump, the upload or the
     *                     verification fails; the row stays FAILED and no artifact stays behind
     */
    public static @NonNull Row backUp(@NonNull DatabaseService service, @NonNull String name) throws IOException {
        return backUp(service, name, InstanceOperationLock.Contention.REFUSE);
    }

    /**
     * {@link #backUp(DatabaseService, String)}, meeting a running dump or restore of the database as
     * {@code contention} says (the engine instance's claim too).
     *
     * @throws DomainRefusal {@code database_busy} when the content lease stayed taken for the whole wait
     */
    public static @NonNull Row backUp(@NonNull DatabaseService service, @NonNull String name,
                                      InstanceOperationLock.@NonNull Contention contention) throws IOException {
        Row database = Models.get(DatabaseModel.class).findByName(name);
        if (database == null) {
            throw new IOException("No managed database named '" + name + "'");
        }
        return DatabaseContentLock.exclusive(database.get(DatabaseModel.ID), contention,
            () -> backUpHeld(service, database, contention));
    }

    private static @NonNull Row backUpHeld(@NonNull DatabaseService service, @NonNull Row database,
                                           InstanceOperationLock.@NonNull Contention contention) throws IOException {
        String name = database.get(DatabaseModel.NAME);
        Destination destination = requireDestination();
        BackupTarget target = destination.target();
        // Health BEFORE the dump: a dead target must not cost the engine a dump nobody can keep.
        target.healthCheck();

        DatabaseDumpModel dumps = Models.get(DatabaseDumpModel.class);
        Row dump = dumps.createEmptyRow();
        Integer databaseId = database.get(DatabaseModel.ID);
        dump.set(DatabaseDumpModel.DATABASE_ID, databaseId);
        dump.set(DatabaseDumpModel.DATABASE_NAME, name);
        dump.set(DatabaseDumpModel.ENGINE, database.get(DatabaseModel.ENGINE));
        dump.set(DatabaseDumpModel.IMAGE, servingImage(database));
        dump.set(DatabaseDumpModel.DB_NAME, database.get(DatabaseModel.DB_NAME));
        dump.set(DatabaseDumpModel.TARGET_ID, destination.targetId());
        dump.set(DatabaseDumpModel.STATUS, BackupStatus.UPLOADING.key());
        dumps.save(dump);

        // AIDEV-NOTE: everything after the UPLOADING row is inside the try, the staging directory too, so every
        // failure settles the row FAILED instead of leaving it uploading until a restart.
        Path staging = null;
        String key = null;
        try {
            staging = stagingDirectory("dump-");
            Stored previous = newest(databaseId);
            // Estimated from the previous dump (the plain dump and its encrypted copy share the disk); the dump's own
            // write keeps the reserve exactly (ReservedDiskOutput), and the encryption is judged on the real size.
            requireRoom(staging, name, Math.multiplyExact(previous == null ? 0 : previous.bytes(), 2));
            String stamp = UtcStamp.now();
            Path plain = service.backupToFile(name, staging, stamp, contention);
            DurableFiles.restrictToOwner(plain);
            String extension = plain.getFileName().toString().substring(stamp.length());
            // AIDEV-NOTE: the controller token leads (a target is shared infrastructure) and the row id is in the
            // key, so two dumps inside one second never write one artifact: the InstanceBackups key rule.
            key = ControllerIdentity.token() + "/database-" + name + "/" + stamp + "-"
                + dump.get(DatabaseDumpModel.ID) + extension + ENCRYPTED_SUFFIX;
            Path encrypted = staging.resolve("dump" + ENCRYPTED_SUFFIX);
            requireRoom(staging, name, Files.size(plain));
            BackupCipher.encrypt(MAGIC, plain, encrypted, FieldEncryption.requireKeyring());
            DurableFiles.restrictToOwner(encrypted);
            Files.delete(plain);
            String localSha = SecureTokens.sha256Hex(encrypted);
            long size = Files.size(encrypted);
            dump.set(DatabaseDumpModel.REMOTE_KEY, key);
            dumps.saveWritten(dump);
            target.store(key, encrypted);
            String storedSha = target.storedSha256(key);
            if (!storedSha.equals(localSha)) {
                throw new IOException("Stored dump does not match what was uploaded (local sha256 " + localSha
                    + ", target holds " + storedSha + ")");
            }
            dump.set(DatabaseDumpModel.STATUS, BackupStatus.COMPLETE.key());
            dump.set(DatabaseDumpModel.SHA256, storedSha);
            dump.set(DatabaseDumpModel.SIZE_BYTES, size);
            dumps.saveWritten(dump);
        } catch (IOException | RuntimeException failed) {
            if (key != null) {
                try {
                    target.delete(key);
                } catch (IOException | RuntimeException cleanupFailed) {
                    Blast.log("DUMP: could not remove partial artifact", key, ":",
                        HohenheimViolations.reasonOf(cleanupFailed));
                }
            }
            try {
                dump.set(DatabaseDumpModel.STATUS, BackupStatus.FAILED.key());
                dump.set(DatabaseDumpModel.ERROR, HohenheimViolations.reasonOf(failed));
                dumps.saveWritten(dump);
            } catch (RuntimeException unrecorded) {
                // The dump's own failure is the one the caller reads; the boot settle ends the row.
                failed.addSuppressed(unrecorded);
                Blast.log("DUMP: could not record the failure of dump", dump.get(DatabaseDumpModel.ID), ":",
                    HohenheimViolations.reasonOf(unrecorded));
            }
            throw failed;
        } finally {
            FileTrees.deleteQuietly(staging);
        }
        if (databaseId != null) {
            RETENTION.sweep(databaseId, DatabaseBackups::delete);
        }
        Blast.log("DUMP: database", name, "stored on target", destination.name(), "as", key);
        return dump;
    }

    /** The image the database's engine runs, which a restore into a new database runs again. */
    private static @NonNull String servingImage(@NonNull Row database) {
        return EngineHost.serving(database).resolvedImage();
    }

    /** What a dump leaves free on its staging disk at the least: the disk the engines' data lives on, often. */
    static final long DUMP_FREE_RESERVE_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * Refuses a dump step its staging disk has no room for: {@code bytes} plus {@link #DUMP_FREE_RESERVE_BYTES} must
     * be free there.
     *
     * AIDEV-NOTE: on Robbedoes (2026-10-30) a 5 GB mongo dump filled the disk every night and finally took the shared
     * engine down with it; a refusal here costs one night's dump of one database, in words, and nothing else. Asked
     * twice: before the dump, with twice the previous dump as its estimate (a database never dumped is judged by the
     * reserve alone), and before the encryption, with the plain dump's real size.
     *
     * @throws DumpOverCap naming what is free and what was needed
     */
    private static void requireRoom(@NonNull Path staging, @NonNull String name, long bytes) throws IOException {
        judgeRoom(name, Files.getFileStore(staging).getUsableSpace(), bytes);
    }

    /**
     * The DECISION half of {@link #requireRoom}, reachable without a disk to measure.
     *
     * @param bytes what the step writes, before the reserve
     * @throws DumpOverCap naming what is free and what was needed
     */
    public static void judgeRoom(@NonNull String name, long freeBytes, long bytes) throws IOException {
        long needed = Math.addExact(bytes, DUMP_FREE_RESERVE_BYTES);
        if (freeBytes < needed) {
            throw DumpOverCap.noRoom(name, freeBytes, needed);
        }
    }

    /**
     * {@link #backUp}, where a failure is logged and raised as the {@code BACKUP_FAILED} alert instead of thrown: one
     * database's failure never costs another its backup.
     *
     * AIDEV-NOTE: the catch is Exception, not IOException. The 2026-08-31 incident was an OutOfMemoryError from the
     * then-buffered dump aborting the whole nightly loop; the dump now streams (ManagedDatabase.backupToFile), so no
     * Error is expected here, and an Error that still occurs fails its caller loudly.
     *
     * @return null when the dump was stored, else the failure in one line
     */
    public static @Nullable String backUpOrAlert(@NonNull DatabaseService service, @NonNull String name,
                                                 InstanceOperationLock.@NonNull Contention contention) {
        try {
            backUp(service, name, contention);
            return null;
        } catch (Exception e) {
            Blast.log("BACKUP: database", name, "failed:", e.getMessage());
            // A refusal carrying the operator's words (the dump cap, its sizes worded) is read in them.
            Microcopy words = DumpOverCap.wordsOf(e);
            Microcopy body = HohenheimMicrocopy.ALERT.of("database_backup_failed_body").withArg("name", name);
            Alerts.trySend(NotificationEvents.BACKUP_FAILED, Alerts.about(DatabaseModel.MODEL_ID, name),
                HohenheimMicrocopy.ALERT.of("database_backup_failed_subject").withArg("name", name),
                words != null ? body.withArg("reason", words) : body.withArg("reason", String.valueOf(e.getMessage())));
            return name + ": " + e.getMessage();
        }
    }

    /**
     * "Back up now": refused here while a dump or restore of the database runs, else {@link #backUpOrAlert} on the
     * provisioning pool as the system's own work, refusing (never queueing) a race it lost there.
     *
     * @throws DomainRefusal {@code database_busy} while a dump or restore of it runs
     */
    public static void backUpInBackground(@NonNull Row database) {
        DatabaseContentLock.requireIdle(database.get(DatabaseModel.ID));
        String name = database.get(DatabaseModel.NAME);
        DatabaseService.submit(() -> backUpOrAlert(new DatabaseService(), name,
            InstanceOperationLock.Contention.REFUSE));
    }

    /** @return one database's complete dumps, newest first (by id: two dumps inside one second share a moment) */
    public static @NonNull List<Stored> stored(@Nullable Integer databaseId) {
        if (databaseId == null) {
            return List.of();
        }
        List<Stored> dumps = new ArrayList<>();
        for (Row dump : completeNewestFirst().where(DatabaseDumpModel.DATABASE_ID.eq(databaseId)).all()) {
            dumps.add(Stored.of(dump));
        }
        return List.copyOf(dumps);
    }

    /** @return the newest complete dump, or null when there is none */
    public static @Nullable Stored newest(@Nullable Integer databaseId) {
        return databaseId == null ? null : newestOf(List.of(databaseId)).get(databaseId);
    }

    /** @return each listed database's newest complete dump, in one read; a database without one is absent */
    public static @NonNull Map<Integer, Stored> newestOf(@NonNull Collection<Integer> databaseIds) {
        Map<Integer, Stored> newest = new HashMap<>();
        for (Map.Entry<Integer, Row> first : FirstPerOwner.of(completeNewestFirst(), DatabaseDumpModel.DATABASE_ID,
                databaseIds).entrySet()) {
            newest.put(first.getKey(), Stored.of(first.getValue()));
        }
        return newest;
    }

    /** @return each listed database's newest dump row whatever its status, in one read; one without any is absent */
    public static @NonNull Map<Integer, Row> latestAttemptOf(@NonNull Collection<Integer> databaseIds) {
        return FirstPerOwner.of(Models.get(DatabaseDumpModel.class).find().orderBy(DatabaseDumpModel.ID,
            SortOrder.DESC), DatabaseDumpModel.DATABASE_ID, databaseIds);
    }

    private static @NonNull QueryBuilder<Row> completeNewestFirst() {
        return Models.get(DatabaseDumpModel.class).find()
            .where(DatabaseDumpModel.STATUS.eq(BackupStatus.COMPLETE.key()))
            .orderBy(DatabaseDumpModel.ID, SortOrder.DESC);
    }

    /**
     * Downloads a complete dump into {@code workDir}, re-hashes it against its row and decrypts it.
     *
     * @return the verified plaintext dump; the caller deletes {@code workDir}
     * @throws IOException naming why the dump cannot be used: not complete, missing on its target, its checksum or
     *                     its GCM tag not matching
     */
    public static @NonNull Path fetchVerified(@NonNull Row dump, @NonNull Path workDir) throws IOException {
        String key = dump.get(DatabaseDumpModel.REMOTE_KEY);
        if (!DatabaseDumpModel.isRestorable(dump) || key == null || !key.endsWith(ENCRYPTED_SUFFIX)) {
            throw new IOException("Dump " + dump.get(DatabaseDumpModel.ID) + " is not complete");
        }
        BackupTarget target;
        try {
            target = BackupTargetKinds.targetFor(dump.get(DatabaseDumpModel.TARGET_ID));
        } catch (Violations unavailable) {
            throw new IOException("The backup target of dump " + dump.get(DatabaseDumpModel.ID)
                + " is unavailable: " + HohenheimViolations.reasonOf(unavailable), unavailable);
        }
        Files.createDirectories(workDir);
        Path encrypted = workDir.resolve("dump" + ENCRYPTED_SUFFIX);
        if (!target.exists(key)) {
            throw new IOException("The stored dump " + key + " is missing on its backup target");
        }
        target.retrieve(key, encrypted);
        DurableFiles.restrictToOwner(encrypted);
        String sha = SecureTokens.sha256Hex(encrypted);
        if (!sha.equals(dump.get(DatabaseDumpModel.SHA256))) {
            throw new IOException("The stored dump " + key + " does not match its recorded checksum (recorded sha256 "
                + dump.get(DatabaseDumpModel.SHA256) + ", found " + sha + "); it is refused whole");
        }
        String plainName = key.substring(key.lastIndexOf('/') + 1, key.length() - ENCRYPTED_SUFFIX.length());
        Path plain = workDir.resolve(plainName);
        // Created owner-only and empty before a byte is decrypted into it.
        Files.deleteIfExists(plain);
        Files.createFile(plain);
        DurableFiles.restrictToOwner(plain);
        BackupCipher.decrypt(MAGIC, encrypted, plain, FieldEncryption.requireKeyring());
        Files.delete(encrypted);
        return plain;
    }

    /**
     * Removes a dump's artifact from its target, then its row ({@link UploadLedger#delete(Row)}).
     *
     * @throws Violations {@code backup_delete_failed} when the target cannot be reached; the row stays
     */
    public static void delete(@NonNull Row dump) {
        LEDGER.delete(dump);
    }

    /**
     * Boot recovery: settles every dump a killed controller left uploading ({@link UploadLedger}) and sweeps what it
     * left in the staging area.
     */
    public static void recoverInterrupted() {
        LEDGER.settleInterrupted();
        sweepStaging();
    }

    /**
     * Removes every staging entry a previous process wrote: a controller killed between a dump's or a restore's
     * staging and its finally block leaves a PLAINTEXT dump there.
     *
     * AIDEV-NOTE: an entry this process wrote is skipped ({@link BootSettle}): the sweep runs off the boot thread, so
     * a dump started right after boot may already stage, and no work of an earlier process can still own one.
     */
    static void sweepStaging() {
        Path root = stagingRoot();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> entries;
        try (Stream<Path> listed = Files.list(root)) {
            entries = listed.toList();
        } catch (IOException | UncheckedIOException unreadable) {
            Blast.log("DUMP: could not read the staging area", root, ":", unreadable.getMessage());
            return;
        }
        for (Path entry : entries) {
            Instant written;
            try {
                written = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant();
            } catch (IOException unreadable) {
                written = null;
            }
            if (BootSettle.writtenByThisProcess(written)) {
                continue;
            }
            IOException failed = FileTrees.delete(entry);
            Blast.log("DUMP: swept staging entry", entry, "an earlier process left",
                failed == null ? "" : "- not fully removed: " + failed.getMessage());
        }
    }

    /** @return the {@code database.backup_path} directory, as every reader of that setting resolves it */
    public static @NonNull Path backupRoot() {
        return Path.of(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_PATH));
    }

    /** @return where dumps are staged before upload; never a resting place */
    public static @NonNull Path stagingRoot() {
        return backupRoot().resolve(STAGING_SUBDIRECTORY);
    }

    /**
     * Creates a fresh directory under the staging root, both owner-only: a plaintext dump passes through it.
     *
     * @return the new directory; the caller deletes it
     * @throws IOException   when the root or the directory cannot be made
     * @throws DomainRefusal {@code database_staging_unsafe} naming the directory and why when it is not a directory of
     *                       its own or cannot be narrowed to its owner; nothing is staged in it
     */
    public static @NonNull Path stagingDirectory(@NonNull String prefix) throws IOException {
        Path root = stagingRoot();
        Files.createDirectories(root);
        restrictToOwner(root);
        Path job = Files.createTempDirectory(root, prefix);
        try {
            restrictToOwner(job);
        } catch (DomainRefusal unsafe) {
            FileTrees.deleteQuietly(job);
            throw unsafe;
        }
        return job;
    }

    /**
     * Narrows a staging directory to its owner and reads it back, refusing when it cannot be.
     *
     * AIDEV-NOTE: fail CLOSED, a non-POSIX filesystem included: a dump or restore never writes plaintext into a
     * directory others may enter. A symbolic link is refused before anything follows it, so a planted link can never
     * redirect the narrowing (or the plaintext) to another directory.
     *
     * @throws DomainRefusal {@code database_staging_unsafe} naming the directory and why
     */
    private static void restrictToOwner(@NonNull Path directory) {
        String why;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            why = "it is not a directory of its own (a symbolic link or another file)";
        } else {
            try {
                Files.setPosixFilePermissions(directory, OWNER_ONLY_DIRECTORY);
                Set<PosixFilePermission> found = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
                if (found.equals(OWNER_ONLY_DIRECTORY)) {
                    return;
                }
                why = "it reads back as " + PosixFilePermissions.toString(found);
            } catch (IOException | UnsupportedOperationException | SecurityException failed) {
                why = HohenheimViolations.reasonOf(failed);
            }
        }
        throw new DomainRefusal(HohenheimRefusalReason.DATABASE_STAGING_UNSAFE,
            "the dump staging directory " + directory + " cannot be restricted to its owner: " + why,
            HohenheimMicrocopy.VIOLATIONS.of("database_staging_unsafe_detail")
                .withArg("path", directory.toString()).withArg("reason", why));
    }
}
