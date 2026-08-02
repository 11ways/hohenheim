package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.server.orm.backup.RecoveryArchive;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Control-plane recovery archives: hohenheim's OWN database plus the field-encryption keyring
 * as one verified unit ({@link RecoveryArchive}), because either half alone is unrecoverable.
 * This is the thin wiring over the zenit mechanism -- distinct from {@link BackupDatabases},
 * which dumps managed TENANT databases.
 *
 * The default destination lives under {@code database.backup_path}, a LOCAL directory: these
 * archives protect against a lost or corrupted working copy, NOT against losing the host.
 * Off-host transfer is a deployment concern and is deliberately not claimed here. The archive
 * contains the master keys in the clear -- treat the backup directory like the keyring file.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class ControlPlaneBackups {

    /** Subdirectory of the backup path holding control-plane archives. */
    public static final String SUBDIRECTORY = "control-plane";

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private ControlPlaneBackups() {}

    /**
     * Back up the live control-plane database and keyring using the configured backup path
     * and retention.
     *
     * @return the created, already-verified archive
     */
    public static @NonNull Path backupNow() throws IOException {
        Path root = Path.of(HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.BACKUP_PATH))
            .resolve(SUBDIRECTORY);
        int retention = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION);
        return backupNow(HohenheimDatabase.datasource(), FieldEncryption.requireKeyring(),
            root, retention);
    }

    /**
     * Back up one datasource + keyring pair into the given directory and prune old archives.
     *
     * @param retention how many archives to keep; 0 or less keeps everything
     * @return the created archive, verified end to end before this returns
     */
    public static @NonNull Path backupNow(@NonNull Datasource datasource,
                                          @NonNull EncryptionKeyring keyring,
                                          @NonNull Path directory,
                                          int retention) throws IOException {
        Path archive = directory.resolve("control-plane-" + STAMP.format(Instant.now()) + ".zrec");
        RecoveryArchive.Manifest manifest = RecoveryArchive.create(datasource, keyring, archive);
        BackupDatabases.pruneOldBackups(directory, retention);
        Blast.log("TASK: control-plane backup written and verified:", archive.toString(),
            "(db", manifest.databaseSize(), "bytes, keyring", manifest.keyIds().size(), "keys)");
        return archive;
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
}
