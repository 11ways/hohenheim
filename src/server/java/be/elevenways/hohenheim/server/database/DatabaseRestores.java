package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Restores a dump into a managed database: a STORED dump ({@link DatabaseDumpModel}) into a new database of the
 * dump's kind and image, or in place into the database it was taken from; an uploaded dump in place into the database
 * the Restore tab names. Every in-place restore takes a fresh dump of the database first.
 *
 * AIDEV-NOTE: there is ONE in-place restore ({@link #replace}): the Backups tab's and the Restore tab's upload both
 * take the database's {@link DatabaseContentLock} first, then fetch the dump, then take the pre-restore dump and load,
 * all under it, so no dump or restore runs between them. A dump carries --clean (ManagedDatabase.Engine.dumpCommand),
 * so loading one replaces same-named objects; the pre-restore dump is what makes that recoverable. A failed load is
 * refused in words that say whether the database is as it was (an engine that restores atomically) or may be mixed,
 * then naming the pre-restore dump to recover from.
 *
 * AIDEV-NOTE: every restore REFUSES while another dump or restore of the database runs ({@code database_busy}),
 * never queueing: the upload runs on a request thread and the stored dump's restores in a run. What a restore refuses
 * before its pre-restore dump costs nothing: an ephemeral database whose engine restores by restart, and a dump that
 * creates or connects to a database of its own (ManagedDatabase.Engine.databaseSwitchIn), which a load into one
 * database cannot honour.
 *
 * AIDEV-NOTE: there is no second restore implementation here. A stored dump is fetched and verified
 * ({@link DatabaseBackups#fetchVerified}) BEFORE anything is created or overwritten, and every load goes through
 * {@link DatabaseService#restoreFromFile}, which refuses a tenant-originated call: restores are operator acts. A
 * restore into a new database is DEDICATED on the dump's image, so the same engine version serves it; the operator
 * moves it onto a shared engine afterwards if wanted. Its cleanup acts on the record id its own insert created, never
 * on the name: a name another record holds is refused by that insert, and a refused insert removes nothing.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DatabaseRestores {

    /** How much of the offending statement a whole-server refusal quotes. */
    private static final int QUOTED_STATEMENT = 120;

    private DatabaseRestores() {
    }

    /**
     * Creates a new dedicated database {@code newName} of the dump's engine and image, on the source's host (this
     * one when the source is gone), and loads the dump into it; a failed provision or load destroys the record this
     * call created.
     *
     * @return the new database's record
     * @throws Violations    {@code database_name_taken} when a record holds the name; nothing is created or removed
     * @throws DomainRefusal {@code database_dump_unusable} when the dump is not complete, missing or tampered with;
     *                       {@code database_dump_whole_server} for a dump of more than one database;
     *                       {@code database_restore_failed} when the failed restore's database could not be removed
     */
    public static @NonNull Row restoreToNew(@NonNull DatabaseService service, int dumpId, @NonNull String newName) {
        DatabaseModel.requireValidName(DatabaseModel.NAME.getName(), newName);
        Row dump = requireDump(dumpId);
        ManagedDatabase.Engine engine = engineOf(dump);
        Row source = sourceOf(dump);
        Path work = null;
        try {
            work = workDirectory(dumpId);
            Path plain = fetch(dump, work);
            requireOneDatabase(engine, newName, plain);
            String user = source != null ? source.get(DatabaseModel.DB_USER) : null;
            String database = dump.get(DatabaseDumpModel.DB_NAME);
            String server = ServerModel.nameOf(source != null ? source.get(DatabaseModel.SERVER_ID) : null);
            // The insert refuses a taken name before any record exists; from here on the record is this call's own.
            Row inserted = service.insertRecord(newName, engine, dump.get(DatabaseDumpModel.IMAGE),
                user == null || user.isBlank() ? "appuser" : user, Secrets.generatePassword(),
                database == null || database.isBlank() ? "app" : database, false, server, ResourceLimits.none(),
                DatabaseModel.STATUS_PROVISIONING, DatabaseModel.PLACEMENT_DEDICATED, null);
            int createdId = inserted.get(DatabaseModel.ID);
            try {
                service.provisionInserted(inserted);
                Row created = Objects.requireNonNull(Models.get(DatabaseModel.class).findById(createdId),
                    "the database the restore created");
                // Under the new database's own content lock: no dump of it may capture the half-loaded state.
                DatabaseContentLock.exclusive(createdId, InstanceOperationLock.Contention.REFUSE, () -> {
                    load(service, dump, created, plain);
                    return null;
                });
                Blast.log("RESTORE: dump", dumpId, "restored into new database", newName);
                return created;
            } catch (IOException | RuntimeException failed) {
                removeHalfRestored(service, createdId, newName, failed);
                throw failed;
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } finally {
            FileTrees.deleteQuietly(work);
        }
    }

    /**
     * Restores the stored dump into the database it was taken from, after storing a fresh dump of that database (the
     * pre-restore backup, which the confirmation names) so the restore can itself be undone.
     *
     * @return the pre-restore dump's row
     * @throws DomainRefusal {@code database_dump_source_gone}, {@code database_dump_kind_mismatch},
     *                       {@code database_busy}, {@code database_dump_unusable} or
     *                       {@code database_dump_whole_server}, each before anything is dumped or overwritten;
     *                       {@code database_restore_failed} when the load failed after the pre-restore dump
     * @throws UnsupportedOperationException for an ephemeral database its engine cannot restore; nothing was dumped
     */
    public static @NonNull Row restoreInPlace(@NonNull DatabaseService service, int dumpId) {
        Row dump = requireDump(dumpId);
        Row database = sourceOf(dump);
        if (database == null) {
            throw new DomainRefusal(HohenheimRefusalReason.DATABASE_DUMP_SOURCE_GONE,
                "dump " + dumpId + " names database " + dump.get(DatabaseDumpModel.DATABASE_ID) + ", which is gone");
        }
        requireSameKind(dump, database);
        Path work = null;
        try {
            Path directory = workDirectory(dumpId);
            work = directory;
            Row before = replace(service, database, () -> fetch(dump, directory));
            Blast.log("RESTORE: dump", dumpId, "restored in place into", database.get(DatabaseModel.NAME));
            return before;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } finally {
            FileTrees.deleteQuietly(work);
        }
    }

    /**
     * Restores an uploaded dump into the named database in place: the same flow as a stored dump's, its pre-restore
     * dump first.
     *
     * @return the pre-restore dump's row
     * @throws DomainRefusal {@code database_busy} or {@code database_dump_whole_server} before anything was dumped;
     *                       {@code database_restore_failed} when the load failed after the pre-restore dump
     * @throws UncheckedIOException when there is no such database or the pre-restore dump failed; nothing was loaded
     * @throws UnsupportedOperationException for an ephemeral database its engine cannot restore; nothing was dumped
     */
    public static @NonNull Row restoreUploadInPlace(@NonNull DatabaseService service, @NonNull String name,
                                                    @NonNull Path upload) {
        Row database = Models.get(DatabaseModel.class).findByName(name);
        if (database == null) {
            throw new UncheckedIOException(new IOException("No managed database named '" + name + "'"));
        }
        try {
            return replace(service, database, () -> upload);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /**
     * THE in-place restore: under the database's content lock, the dump to load ({@code plaintext}, fetched only once
     * the lock is held), its whole-server check, a fresh dump of the database, then the load.
     *
     * @return the pre-restore dump's row
     * @throws IOException when the pre-restore dump fails; nothing was loaded
     * @throws DomainRefusal {@code database_restore_failed} naming what the failed load left
     */
    private static @NonNull Row replace(@NonNull DatabaseService service, @NonNull Row database,
                                        DatabaseService.@NonNull ThrowingSupplier<Path> plaintext) throws IOException {
        String name = database.get(DatabaseModel.NAME);
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(database.get(DatabaseModel.ENGINE));
        if (engine != null && engine.restoresByRestart()
                && Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL))) {
            throw ManagedDatabase.ephemeralRestore(engine);
        }
        InstanceOperationLock.Contention refuse = InstanceOperationLock.Contention.REFUSE;
        return DatabaseContentLock.exclusive(database.get(DatabaseModel.ID), refuse, () -> {
            Path plain = plaintext.get();
            if (engine != null) {
                requireOneDatabase(engine, name, plain);
            }
            Row before = DatabaseBackups.backUp(service, name, refuse);
            try {
                service.restoreFromFile(name, plain, refuse);
            } catch (UnsupportedOperationException | Violations refused) {
                // Refused before a byte moved (a redis without a persistent data volume, the engine instance's claim
                // held by another operation): the database is as it was, and the pre-restore dump stays stored.
                throw refused;
            } catch (IOException | RuntimeException failed) {
                throw restoreFailed(database, before, failed);
            }
            return before;
        });
    }

    /**
     * Refuses a dump that creates or connects to a database of its own before anything is dumped or created.
     *
     * @throws DomainRefusal {@code database_dump_whole_server} quoting the statement
     */
    private static void requireOneDatabase(ManagedDatabase.@NonNull Engine engine, @NonNull String name,
                                           @NonNull Path plain) throws IOException {
        String statement = engine.databaseSwitchIn(plain);
        if (statement == null) {
            return;
        }
        String quoted = statement.length() <= QUOTED_STATEMENT ? statement
            : statement.substring(0, QUOTED_STATEMENT) + "...";
        throw new DomainRefusal(HohenheimRefusalReason.DATABASE_DUMP_WHOLE_SERVER,
            "the dump for " + name + " creates or connects to a database of its own: " + quoted,
            HohenheimMicrocopy.VIOLATIONS.of("database_dump_whole_server_detail")
                .withArg("name", name).withArg("statement", quoted));
    }

    /** The refusal of a failed in-place load, in words that say what the database holds now. */
    private static @NonNull DomainRefusal restoreFailed(@NonNull Row database, @NonNull Row before,
                                                        @NonNull Exception failed) {
        String name = database.get(DatabaseModel.NAME);
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(database.get(DatabaseModel.ENGINE));
        String reason = HohenheimViolations.reasonOf(failed);
        boolean unchanged = engine != null && engine.restoresAtomically();
        Microcopy words = unchanged
            ? HohenheimMicrocopy.VIOLATIONS.of("database_restore_failed_unchanged_detail")
                .withArg("name", name).withArg("reason", reason)
            : HohenheimMicrocopy.VIOLATIONS.of("database_restore_failed_mixed_detail")
                .withArg("name", name).withArg("reason", reason)
                .withArg("before", before.get(DatabaseDumpModel.CREATED_AT))
                .withArg("dump", String.valueOf((Object) before.get(DatabaseDumpModel.ID)));
        return DomainRefusal.caused(HohenheimRefusalReason.DATABASE_RESTORE_FAILED,
            "in-place restore of " + name + " failed " + (unchanged ? "and was rolled back" : "part way")
                + " after pre-restore dump " + before.get(DatabaseDumpModel.ID) + ": " + reason, null, words, failed);
    }

    /**
     * Destroys the record a failed restore into a new database created, by its id.
     *
     * @throws DomainRefusal {@code database_restore_failed} naming both failures when it cannot be removed
     */
    private static void removeHalfRestored(@NonNull DatabaseService service, int createdId, @NonNull String name,
                                           @NonNull Exception failed) {
        try {
            service.destroyById(createdId, true);
        } catch (IOException | RuntimeException cleanup) {
            String reason = HohenheimViolations.reasonOf(failed);
            String left = HohenheimViolations.reasonOf(cleanup);
            Blast.log("RESTORE: could not remove the half-restored database", name, ":", left);
            DomainRefusal refusal = DomainRefusal.caused(HohenheimRefusalReason.DATABASE_RESTORE_FAILED,
                "restore into new database " + name + " failed (" + reason + ") and it could not be removed: " + left,
                null, HohenheimMicrocopy.VIOLATIONS.of("database_restore_new_cleanup_failed_detail")
                    .withArg("name", name).withArg("reason", reason).withArg("cleanup", left), failed);
            refusal.addSuppressed(cleanup);
            throw refusal;
        }
    }

    /**
     * Loads a verified dump into a database of the same kind through the one restore lane.
     *
     * @throws DomainRefusal {@code database_dump_kind_mismatch} for a database of another engine
     */
    static void load(@NonNull DatabaseService service, @NonNull Row dump, @NonNull Row database, @NonNull Path plain) {
        requireSameKind(dump, database);
        try {
            service.restoreFromFile(database.get(DatabaseModel.NAME), plain, InstanceOperationLock.Contention.REFUSE);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** @throws DomainRefusal {@code database_dump_kind_mismatch} unless the dump and the database share an engine */
    static void requireSameKind(@NonNull Row dump, @NonNull Row database) {
        Object dumped = dump.get(DatabaseDumpModel.ENGINE);
        Object target = database.get(DatabaseModel.ENGINE);
        if (!Objects.equals(dumped, target)) {
            throw new DomainRefusal(HohenheimRefusalReason.DATABASE_DUMP_KIND_MISMATCH,
                "dump " + dump.get(DatabaseDumpModel.ID) + " is " + dumped + ", the database is " + target,
                HohenheimMicrocopy.VIOLATIONS.of("database_dump_kind_mismatch_detail")
                    .withArg("dump", String.valueOf(dumped)).withArg("database", String.valueOf(target)));
        }
    }

    /** @throws DomainRefusal {@code database_dump_unusable} for a dump that is gone or not complete */
    private static @NonNull Row requireDump(int dumpId) {
        Row dump = Models.get(DatabaseDumpModel.class).findById(dumpId);
        if (dump == null || !DatabaseDumpModel.isRestorable(dump)) {
            throw unusable(dumpId, "it is not complete");
        }
        return dump;
    }

    private static ManagedDatabase.@NonNull Engine engineOf(@NonNull Row dump) {
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(dump.get(DatabaseDumpModel.ENGINE));
        if (engine == null) {
            throw unusable(dump.get(DatabaseDumpModel.ID), "its engine '" + dump.get(DatabaseDumpModel.ENGINE)
                + "' is unknown");
        }
        return engine;
    }

    private static @Nullable Row sourceOf(@NonNull Row dump) {
        Integer databaseId = dump.get(DatabaseDumpModel.DATABASE_ID);
        return databaseId == null ? null : Models.get(DatabaseModel.class).findById(databaseId);
    }

    /** @throws DomainRefusal {@code database_dump_unusable} naming why the artifact cannot be used */
    private static @NonNull Path fetch(@NonNull Row dump, @NonNull Path work) {
        try {
            return DatabaseBackups.fetchVerified(dump, work);
        } catch (IOException refused) {
            throw unusable(dump.get(DatabaseDumpModel.ID), String.valueOf(refused.getMessage()));
        }
    }

    private static @NonNull DomainRefusal unusable(Object dumpId, @NonNull String why) {
        return new DomainRefusal(HohenheimRefusalReason.DATABASE_DUMP_UNUSABLE, "dump " + dumpId + ": " + why,
            HohenheimMicrocopy.VIOLATIONS.of("database_dump_unusable_detail")
                .withArg("reason", why));
    }

    private static @NonNull Path workDirectory(int dumpId) throws IOException {
        return DatabaseBackups.stagingDirectory("restore-" + dumpId + "-");
    }
}
