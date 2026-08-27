package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.text.Texts;
import be.elevenways.zenit.server.orm.backup.RecoveryArchive;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Control-plane recovery archives: hohenheim's OWN database plus the field-encryption keyring
 * as one verified unit ({@link RecoveryArchive}), uploaded to a configured
 * {@link BackupTarget}. This is the thin wiring over the zenit mechanism -- distinct from
 * {@link BackupDatabases}, which dumps managed TENANT databases.
 *
 * AIDEV-NOTE: the destination is REQUIRED and it is a target RECORD, never a local directory.
 * This class used to write to {@code database.backup_path} and say in its own docblock that
 * off-host transfer was somebody else's problem -- which meant the archive died in exactly the
 * failure it exists for: one dead disk takes the database, the keyring AND the archive. The
 * local file here is STAGING only and is deleted in a finally block, including when the upload
 * failed: a "backup" nobody can distinguish from a real one, sitting on the doomed disk, is
 * worse than none. An unset or unreachable destination fails the nightly task loudly (which
 * surfaces as a dashboard attention item) instead of quietly writing locally.
 *
 * NOT FIXED, ON PURPOSE, AND YOU MUST KNOW IT: the archive contains the field-encryption MASTER
 * KEYS IN THE CLEAR and its manifest is UNSIGNED. Off-host therefore means "the destination host
 * can decrypt every secret in this database and could substitute an archive we would restore".
 * The transport is authenticated (the target's host record is pinned and operator-confirmed) but
 * the artifact at rest is not. Treat the destination like the keyring file itself.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class ControlPlaneBackups {

    /** Key prefix of control-plane archives on the target; also the retention scope. */
    public static final String KEY_PREFIX = "control-plane/";

    /** Subdirectory of the backup path archives are BUILT in before upload, never kept. */
    public static final String STAGING_SUBDIRECTORY = "control-plane-staging";

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private ControlPlaneBackups() {}

    /** One uploaded, remotely re-hashed archive. */
    public record Archive(@NonNull String key, @NonNull String sha256,
                          RecoveryArchive.@NonNull Manifest manifest) {}

    /**
     * Back up the live control-plane database and keyring to the configured off-host target.
     *
     * @return the created archive, verified BY THE TARGET before this returns
     * @throws IllegalStateException when no destination is configured
     * @throws IOException when the target is unreachable, or the bytes it holds do not match
     */
    public static @NonNull Archive backupNow() throws IOException {
        String name = destinationName();
        int retention = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION);
        return backupNow(HohenheimDatabase.datasource(), FieldEncryption.requireKeyring(),
            requireDestination(), stagingDirectory(), retention, name);
    }

    /**
     * Back up one datasource + keyring pair to a target and prune old archives THERE.
     *
     * Ordering inside the archive is load-bearing and unchanged: the database is snapshotted
     * first and the keyring read second, so the archived keyring is never older than the
     * archived database.
     *
     * @param retention how many archives to keep on the target; 0 or less keeps everything
     * @return the created archive, re-hashed by the target before this returns
     */
    public static @NonNull Archive backupNow(@NonNull Datasource datasource,
                                             @NonNull EncryptionKeyring keyring,
                                             @NonNull BackupTarget target,
                                             @NonNull Path stagingDirectory,
                                             int retention,
                                             @NonNull String destinationName) throws IOException {
        // Before the snapshot: a target that fails at store time already spent the capture.
        target.healthCheck();

        Files.createDirectories(stagingDirectory);
        String fileName = "control-plane-" + STAMP.format(Instant.now()) + ".zrec";
        Path staged = stagingDirectory.resolve(fileName);
        String key = KEY_PREFIX + fileName;

        RecoveryArchive.Manifest manifest;
        String localSha;
        try {
            manifest = RecoveryArchive.create(datasource, keyring, staged);
            localSha = sha256OfFile(staged);
            target.store(key, staged);
        } finally {
            // Always: a surviving local .zrec is indistinguishable from a real backup while
            // being the one thing this class exists to stop producing.
            Files.deleteIfExists(staged);
        }

        String remoteSha = target.storedSha256(key);
        if (!remoteSha.equals(localSha)) {
            target.delete(key);
            throw new IOException("Control-plane archive " + key + " does not match on the target"
                + " (uploaded sha256 " + localSha + ", the target holds " + remoteSha
                + "); the artifact was removed and nothing was kept locally");
        }

        int pruned = prune(target, retention);

        Blast.log("TASK: control-plane backup uploaded and verified off-host:", key,
            "on target", destinationName,
            "(db", manifest.databaseSize(), "bytes, keyring", manifest.keyIds().size(), "keys,",
            pruned, "old archives pruned)");
        return new Archive(key, remoteSha, manifest);
    }

    /**
     * Delete the oldest archives beyond the retention count.
     *
     * The keys are timestamp-suffixed, so lexicographic order IS chronological order.
     *
     * @return how many were removed
     */
    private static int prune(@NonNull BackupTarget target, int retention) throws IOException {
        if (retention <= 0) {
            return 0;
        }
        List<String> keys = new ArrayList<>(target.list(KEY_PREFIX));
        keys.sort(Comparator.reverseOrder());
        int pruned = 0;
        for (int index = retention; index < keys.size(); index++) {
            target.delete(keys.get(index));
            pruned++;
        }
        return pruned;
    }

    /** @return the archives the configured target currently holds, newest first */
    public static @NonNull List<String> listBackups() throws IOException {
        List<String> keys = new ArrayList<>(requireDestination().list(KEY_PREFIX));
        keys.sort(Comparator.reverseOrder());
        return keys;
    }

    /**
     * The configured destination, resolved through the backup-target record and its kind.
     *
     * @throws IllegalStateException when the setting is unset or names no target record; the
     *         message lists the targets that DO exist, because a typo must not read like a
     *         product that has no backups
     */
    // AIDEV-NOTE: resolution is BY NAME and is deterministic only because
    // backup_targets.name carries the backup_targets_name_unique index (InitialMigration).
    // The chain never constrained it, so two targets could legitimately share a name and
    // this .first() picked whichever the store happened to return -- silently uploading the
    // control-plane archive (master keys in the clear) to an arbitrary one of them, while
    // BackupTargetModel's delete refusal protected the other. The name is KEPT rather than
    // swapped for a record id: it is what an operator writes into a settings file and reads
    // back on the dashboard, and the unique index makes it a real identity. If that index
    // ever goes, this must become an id.
    public static @NonNull BackupTarget requireDestination() {
        String name = destinationName();
        Row row = Models.get(BackupTargetModel.class).find()
            .where(BackupTargetModel.NAME.eq(name)).first();
        if (row == null) {
            throw new IllegalStateException(
                "Setting " + HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET.getPath()
                + " names backup target '" + name + "', which does not exist. Configured targets: "
                + knownTargetNames());
        }
        return BackupTargetKinds.targetOf(row);
    }

    /**
     * @throws IllegalStateException when no destination is configured at all -- the deliberate
     *         breaking change: there is no local fallback to degrade to
     */
    private static @NonNull String destinationName() {
        String name = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET);
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                "No control-plane backup destination is configured: set "
                + HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET.getPath()
                + " to the name of a backup target. There is deliberately no local default --"
                + " an archive of this database and its keyring on this host's own disk does not"
                + " survive losing this host, which is the only failure it exists for."
                + " Configured targets: " + knownTargetNames());
        }
        return name;
    }

    private static @NonNull String knownTargetNames() {
        List<String> names = new ArrayList<>();
        for (Row row : Models.get(BackupTargetModel.class).find().all()) {
            names.add(String.valueOf((Object) row.get(BackupTargetModel.NAME)));
        }
        return names.isEmpty() ? "(none configured)" : String.join(", ", names);
    }

    /** Where archives are built before upload; never a resting place. */
    public static @NonNull Path stagingDirectory() {
        return Path.of(HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.BACKUP_PATH))
            .resolve(STAGING_SUBDIRECTORY);
    }

    /**
     * Fetch an archive from the configured target and restore it onto the configured paths.
     *
     * LIMITATION, stated rather than papered over: resolving the target reads a backup-target
     * row and its {@code servers} host record, so this needs a READABLE control-plane database.
     * It covers a lost keyring, a bad upgrade or a rollback -- NOT a lost host. In a total loss
     * the operator copies the artifact down themselves (the destination is an ordinary ssh host)
     * and restores it with {@code --restore-control-plane <path>}. Making the destination
     * resolvable without the database would mean a second, weaker authority over which remote
     * host we trust, which the host-record design deliberately refuses.
     *
     * @param key the archive key as {@link #listBackups} reports it
     * @return the archive's manifest
     */
    public static RecoveryArchive.@NonNull Manifest restoreFromTarget(@NonNull String key)
            throws IOException {
        BackupTarget target = requireDestination();
        Path staging = Files.createTempDirectory("hohenheim-control-plane-restore");
        Path fetched = staging.resolve("archive.zrec");
        try {
            target.retrieve(key, fetched);
            return restore(fetched);
        } finally {
            Files.deleteIfExists(fetched);
            Files.deleteIfExists(staging);
        }
    }

    /**
     * Restore a recovery archive onto the CONFIGURED database and keyring paths. Offline
     * only: must run before the datasource is opened (the {@code --restore-control-plane}
     * boot argument), never against a live server.
     *
     * @return the archive's manifest
     */
    public static RecoveryArchive.@NonNull Manifest restore(@NonNull Path archive) {
        Path database = databaseFile();
        Path keyring = keyringFile();
        RecoveryArchive.Manifest manifest = RecoveryArchive.restore(archive, database, keyring);
        Blast.log("Restored control-plane recovery archive", archive.toString(),
            "-> database", database.toString(), "+ keyring", keyring.toString(),
            "(created", manifest.created() + ", keys", String.join(",", manifest.keyIds()) + ")");
        return manifest;
    }

    /**
     * The control-plane SQLite file the settings point at.
     *
     * @throws IllegalStateException when {@code database.url} is set to something that is not
     *         a plain SQLite file URL (restore replaces a FILE; it cannot address URL extras)
     */
    public static @NonNull Path databaseFile() {
        String url = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.URL);
        if (url == null || url.isBlank()) {
            return Path.of(HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH));
        }
        String prefix = "jdbc:sqlite:";
        if (!url.startsWith(prefix)) {
            throw new IllegalStateException(
                "database.url is '" + url + "', which is not a jdbc:sqlite: file URL;"
                + " a control-plane restore replaces the SQLite file and cannot address this");
        }
        String path = url.substring(prefix.length());
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.isBlank() || path.startsWith(":")) {
            throw new IllegalStateException(
                "database.url '" + url + "' does not name a SQLite FILE; a control-plane"
                + " restore needs a file path to replace");
        }
        return Path.of(path);
    }

    /** The keyring file the zenit encryption settings point at. */
    public static @NonNull Path keyringFile() {
        return Path.of(ServerSettings.VALUES.getValue(ServerSettings.Database.Encryption.KEY_FILE));
    }

    /** @return whether a destination is configured at all (the dashboard's question) */
    public static @Nullable String configuredDestinationName() {
        return Texts.trimmedOrNull(HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET));
    }

    private static @NonNull String sha256OfFile(@NonNull Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return SecureTokens.hex(digest.digest());
    }
}
