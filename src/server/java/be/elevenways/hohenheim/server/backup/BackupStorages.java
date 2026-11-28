package be.elevenways.hohenheim.server.backup;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.protoblast.diagnostic.AtomicFiles;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.storage.PutOptions;
import be.elevenways.zenit.server.storage.StorageAdapter;
import be.elevenways.zenit.server.storage.StorageCheck;
import be.elevenways.zenit.server.storage.StorageException;
import be.elevenways.zenit.server.storage.StorageRoom;
import be.elevenways.zenit.server.storage.Storages;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * How a backup reaches the storage its record names, and what a backup asks of any storage beyond the adapter's own
 * calls: a check before it spends anything, a durable put, a download to a local file, a removal that tidies its
 * namespace, and a listing without what an interrupted upload of the old targets left behind.
 *
 * AIDEV-NOTE: every backup (dumps, app backups, the recovery archive) takes its storage from here, opened by the
 * storage layer through the record's kind ({@link HohenheimStorages}); a name a settings-declared mount holds is
 * refused, never resolved to that mount, so a record can never send its backups somewhere else. Failures reach the
 * callers as {@link IOException}, the contract their settling of a failed row reads.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
public final class BackupStorages {

    /** What an SSH target's interrupted upload left beside its key; such a name is never a committed backup. */
    private static final String SSH_STAGING_SUFFIX = ".part";

    private BackupStorages() {}

    /**
     * @return the storage the record with this id declares
     * @throws Violations {@code backup_target_missing} when no record has the id, or as {@link #of} refuses
     */
    public static @NonNull StorageAdapter forTarget(@Nullable Integer targetId) {
        Row row = targetId == null ? null : Models.get(BackupTargetModel.class).findById(targetId);
        if (row == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_target_missing"));
        }
        return of(row);
    }

    /**
     * @return the storage the record declares, opened through its kind
     * @throws Violations {@code backup_target_name_taken} when a mount of this server's settings holds its name, the
     *         refusal of an SSH-era record's host gate (asked again at every resolution), or
     *         {@code backup_target_invalid} naming why the storage cannot be opened
     */
    public static @NonNull StorageAdapter of(@NonNull Row row) {
        String name = String.valueOf((Object) row.get(BackupTargetModel.NAME));
        if (Storages.has(name)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_target_name_taken").withArg("name", name));
        }
        // Its own refusal first, as it is: the storage layer would only say the name declares nothing.
        try {
            HohenheimStorages.declarationOf(row);
        } catch (Violations refused) {
            throw refused;
        } catch (RuntimeException unreadable) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_target_invalid")
                .withArg("name", name).withArg("reason", HohenheimViolations.reasonOf(unreadable)));
        }
        try {
            return Storages.get(name);
        } catch (Violations refused) {
            throw refused;
        } catch (RuntimeException unusable) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("backup_target_invalid")
                .withArg("name", name)
                .withArg("reason", HohenheimViolations.reasonOf(unusable.getCause() != null ? unusable.getCause()
                    : unusable)));
        }
    }

    /**
     * Checks the storage can be reached and written now, before a backup spends any downtime or disk on it.
     *
     * @throws IOException carrying the check's own diagnostic when it cannot
     */
    public static void requireUsable(@NonNull StorageAdapter storage) throws IOException {
        StorageCheck check = storage.check();
        if (!check.isUsable()) {
            throw new IOException(check.diagnostic());
        }
    }

    /**
     * Stores a local file under the key so it survives a power loss once this returns; nothing is under the key
     * until every byte landed.
     *
     * @throws IOException when the file cannot be read or the storage fails
     */
    public static void store(@NonNull StorageAdapter storage, @NonNull String key, @NonNull Path file)
            throws IOException {
        try {
            storage.put(key, file, PutOptions.builder().contentLength(Files.size(file)).durable().build());
        } catch (StorageException failed) {
            throw new IOException(failed.getMessage(), failed);
        }
    }

    /** @throws IOException when the key is absent or the storage fails */
    public static @NonNull String storedSha256(@NonNull StorageAdapter storage, @NonNull String key)
            throws IOException {
        try {
            return storage.storedSha256(key);
        } catch (StorageException failed) {
            throw new IOException(failed.getMessage(), failed);
        }
    }

    /** @throws IOException when the storage fails; an absent key answers false */
    public static boolean exists(@NonNull StorageAdapter storage, @NonNull String key) throws IOException {
        try {
            return storage.exists(key);
        } catch (StorageException failed) {
            throw new IOException(failed.getMessage(), failed);
        }
    }

    /**
     * Downloads what the key holds into a local file, replacing it.
     *
     * @throws IOException when the key is absent or the storage or the local disk fails
     */
    public static void retrieve(@NonNull StorageAdapter storage, @NonNull String key, @NonNull Path destination)
            throws IOException {
        try (InputStream in = storage.get(key)) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (StorageException failed) {
            throw new IOException(failed.getMessage(), failed);
        }
    }

    /**
     * Every committed key under a prefix, in no defined order; the staged names an interrupted upload of the old
     * folder and SSH targets left are not keys and never listed, so retention never counts them.
     *
     * @throws IOException when the storage fails
     */
    public static @NonNull List<String> list(@NonNull StorageAdapter storage, @NonNull String prefix)
            throws IOException {
        List<String> keys;
        try {
            keys = storage.list(prefix);
        } catch (StorageException failed) {
            throw new IOException(failed.getMessage(), failed);
        }
        List<String> committed = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (!key.endsWith(SSH_STAGING_SUFFIX) && !AtomicFiles.isStaged(Path.of(key))) {
                committed.add(key);
            }
        }
        return committed;
    }

    /**
     * Makes sure nothing is under the key (absent is success), then removes the folders left empty in the key's
     * namespace, its first segment: a controller's own folder on a shared storage, never another's.
     *
     * @throws IOException when the storage fails
     */
    public static void delete(@NonNull StorageAdapter storage, @NonNull String key) throws IOException {
        try {
            storage.deleteIfExists(key);
            int slash = key.indexOf('/');
            if (slash > 0) {
                storage.deleteFolder(key.substring(0, slash));
            }
        } catch (StorageException failed) {
            throw new IOException(failed.getMessage(), failed);
        }
    }

    /**
     * @return the bytes the storage can still take, empty when it cannot tell (an S3 store): a caller then judges
     *         nothing rather than a number standing for unknown
     * @throws IOException when the storage can tell but the read fails
     */
    public static @NonNull Optional<Long> freeBytes(@NonNull StorageAdapter storage) throws IOException {
        try {
            return storage.room().map(StorageRoom::freeBytes);
        } catch (StorageException failed) {
            throw new IOException(failed.getMessage(), failed);
        }
    }
}
