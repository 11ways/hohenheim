package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scheduled task that dumps each running, text-dumpable managed database to a timestamped file
 * under the backup directory, then prunes old dumps per the retention setting. Redis/Mongo and
 * stopped databases are skipped (no text dump available / nothing to connect to). Runs daily.
 */
public class BackupDatabases extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Back up managed databases";

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @Override
    public @NonNull BackupDatabases newTask() {
        return new BackupDatabases();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("0 3 * * *")),
            HohenheimRoles.Role.DATABASES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    /** One run's honest tally: what was dumped, and every database that was not, with why. */
    public record Outcome(int backedUp, @NonNull List<String> failures) {}

    @Override
    public void executor(TaskContext ctx) {
        Outcome outcome = backupAll(new DatabaseService());
        for (String failure : outcome.failures()) {
            ctx.report(failure);
        }
        if (!outcome.failures().isEmpty()) {
            // A partial run must not read as a green run: the throw marks the history
            // row FAILED while every database that could be dumped already was.
            throw new IllegalStateException("Backed up " + outcome.backedUp()
                + " databases; " + outcome.failures().size() + " failed: "
                + String.join("; ", outcome.failures()));
        }
    }

    /** Dump every running, text-dumpable managed database, then prune old dumps. */
    public static Outcome backupAll(DatabaseService databaseService) {
        Path backupRoot = Path.of(HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.BACKUP_PATH));
        int retention = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION);

        int backedUp = 0;
        List<String> failures = new ArrayList<>();
        for (DatabaseService.Summary db : databaseService.summaries()) {
            if (!db.running()) {
                continue;   // can't dump a stopped container
            }
            if (db.ephemeral()) {
                // tmpfs databases are declared throwaway: dumping them wastes
                // space and a dump failure would fire a false BACKUP_FAILED alert.
                continue;
            }
            // AIDEV-NOTE: the catch is Exception, not IOException: one database's failure
            // must never cost the others their backup. The 2026-08-31 incident was an
            // OutOfMemoryError from the then-buffered dump aborting the whole loop; the
            // dump now streams (ManagedDatabase.backupToFile), so no Error is expected
            // here, and an Error that still occurs should fail the run loudly.
            try {
                Path dbDir = backupRoot.resolve(db.name());
                databaseService.backupToFile(db.name(), dbDir, STAMP.format(Instant.now()));
                pruneOldBackups(dbDir, retention);
                backedUp++;
            } catch (Exception e) {
                failures.add(db.name() + ": " + e.getMessage());
                Blast.log("TASK: BackupDatabases failed for", db.name(), ":", e.getMessage());
                try {
                    Alerts.send(NotificationEvents.BACKUP_FAILED,
                        "Database backup failed: " + db.name(),
                        "The scheduled backup of '" + db.name() + "' failed: " + e.getMessage());
                } catch (Exception notifyError) {
                    Blast.log("TASK: could not send backup-failure notification -", notifyError.getMessage());
                }
            }
        }
        Blast.log("TASK: BackupDatabases backed up", backedUp, "databases");
        return new Outcome(backedUp, failures);
    }

    /**
     * Keep the newest {@code retention} files in a backup directory (timestamp filenames sort
     * chronologically), delete older ones; retention 0 or less keeps everything. Shared with
     * the control-plane backup task.
     */
    public static void pruneOldBackups(Path dir, int retention) throws IOException {
        if (retention <= 0) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> dumps = files
                .filter(Files::isRegularFile)
                .sorted(Comparator.reverseOrder())
                .toList();
            for (int i = retention; i < dumps.size(); i++) {
                Files.deleteIfExists(dumps.get(i));
            }
        }
    }
}
