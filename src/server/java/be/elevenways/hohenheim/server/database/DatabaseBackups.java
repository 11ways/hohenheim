package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The stored dumps of managed databases on the controller: where the nightly task and "Back up now" write them, and
 * what the Databases list and a database's overview read back.
 *
 * AIDEV-NOTE: the directory IS the record. There is no backup table: a dump's time is its file's modification time
 * and its size the file's, so a dump pruned by retention or removed by hand simply stops being listed, and the list
 * can never claim a backup that is not on disk.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class DatabaseBackups {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /**
     * One dump on disk.
     *
     * @param file  the dump file's name
     * @param at    when it was written
     * @param bytes its size
     */
    public record Stored(@NonNull String file, @NonNull Instant at, long bytes) {}

    private DatabaseBackups() {
    }

    /** @return the directory holding one database's dumps */
    public static @NonNull Path directoryOf(@NonNull String name) {
        return Path.of(Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_PATH)).resolve(name);
    }

    /**
     * Dumps one database next to its earlier dumps, then prunes them to the retention setting.
     *
     * @return the written dump
     * @throws IOException when the dump fails
     */
    public static @NonNull Path backUp(@NonNull DatabaseService service, @NonNull String name) throws IOException {
        Path directory = directoryOf(name);
        Path written = service.backupToFile(name, directory, STAMP.format(Now.instant()));
        prune(directory, Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION));
        return written;
    }

    /**
     * {@link #backUp}, where a failure is logged and raised as the {@code BACKUP_FAILED} alert instead of thrown: one
     * database's failure never costs another its backup.
     *
     * AIDEV-NOTE: the catch is Exception, not IOException. The 2026-08-31 incident was an OutOfMemoryError from the
     * then-buffered dump aborting the whole nightly loop; the dump now streams (ManagedDatabase.backupToFile), so no
     * Error is expected here, and an Error that still occurs fails its caller loudly.
     *
     * @return null when the dump was written, else the failure in one line
     */
    public static @Nullable String backUpOrAlert(@NonNull DatabaseService service, @NonNull String name) {
        try {
            backUp(service, name);
            return null;
        } catch (Exception e) {
            Blast.log("BACKUP: database", name, "failed:", e.getMessage());
            Alerts.trySend(NotificationEvents.BACKUP_FAILED, Alerts.about(DatabaseModel.MODEL_ID, name),
                Microcopy.of("database_backup_failed_subject").withFilter("scope", "alert").withArg("name", name),
                Microcopy.of("database_backup_failed_body").withFilter("scope", "alert")
                    .withArg("name", name).withArg("reason", String.valueOf(e.getMessage())));
            return name + ": " + e.getMessage();
        }
    }

    /** "Back up now": {@link #backUpOrAlert} on the provisioning pool, as the system's own work. */
    public static void backUpInBackground(@NonNull String name) {
        DatabaseService.submit(() -> backUpOrAlert(new DatabaseService(), name));
    }

    /**
     * Keeps the newest {@code retention} files in a dump directory (timestamp names sort chronologically) and deletes
     * the older ones; a retention of 0 or less keeps everything.
     */
    public static void prune(@NonNull Path directory, int retention) throws IOException {
        if (retention <= 0) {
            return;
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> dumps = files.filter(Files::isRegularFile).sorted(Comparator.reverseOrder()).toList();
            for (int i = retention; i < dumps.size(); i++) {
                Files.deleteIfExists(dumps.get(i));
            }
        }
    }

    /** @return one database's dumps, newest first; empty when it has none or the directory cannot be read */
    public static @NonNull List<Stored> stored(@NonNull String name) {
        List<Stored> dumps = new ArrayList<>();
        try (Stream<Path> files = Files.list(directoryOf(name))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try {
                    dumps.add(new Stored(file.getFileName().toString(),
                        Files.getLastModifiedTime(file).toInstant(), Files.size(file)));
                } catch (NoSuchFileException pruned) {
                    // Pruned between the listing and the read: it is no longer a stored dump.
                }
            }
        } catch (IOException none) {
            return List.of();
        }
        dumps.sort(Comparator.comparing(Stored::at).reversed().thenComparing(Stored::file));
        return List.copyOf(dumps);
    }

    /** @return the newest dump, or null when there is none */
    public static @Nullable Stored newest(@NonNull String name) {
        List<Stored> dumps = stored(name);
        return dumps.isEmpty() ? null : dumps.getFirst();
    }
}
