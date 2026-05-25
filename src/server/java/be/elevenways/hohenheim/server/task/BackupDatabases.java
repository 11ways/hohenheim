package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.protoblast.common.Blast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scheduled task that dumps each running, text-dumpable managed database to a timestamped file
 * under the backup directory, then prunes old dumps per the retention setting. Redis/Mongo and
 * stopped databases are skipped (no text dump available / nothing to connect to).
 */
public class BackupDatabases implements Runnable {

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final DatabaseService databaseService;

    public BackupDatabases() {
        this(new DatabaseService());
    }

    public BackupDatabases(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @Override
    public void run() {
        Path backupRoot = Path.of(HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.BACKUP_PATH));
        int retention = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.BACKUP_RETENTION);

        int backedUp = 0;
        for (DatabaseService.Summary db : databaseService.summaries()) {
            if (!db.engine().equals("postgres") && !db.engine().equals("mysql")) {
                continue;   // no text dump for redis/mongo yet
            }
            if (!db.running()) {
                continue;   // can't dump a stopped container
            }
            try {
                Path dbDir = backupRoot.resolve(db.name());
                Files.createDirectories(dbDir);
                databaseService.backupToFile(db.name(), dbDir.resolve(STAMP.format(Instant.now()) + ".sql"));
                pruneOldBackups(dbDir, retention);
                backedUp++;
            } catch (IOException e) {
                Blast.log("TASK: BackupDatabases failed for", db.name(), ":", e.getMessage());
            }
        }
        Blast.log("TASK: BackupDatabases backed up", backedUp, "databases");
    }

    // Keep the newest `retention` dumps (timestamp filenames sort chronologically), delete older.
    private static void pruneOldBackups(Path dir, int retention) throws IOException {
        if (retention <= 0) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> dumps = files
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted(Comparator.reverseOrder())
                .toList();
            for (int i = retention; i < dumps.size(); i++) {
                Files.deleteIfExists(dumps.get(i));
            }
        }
    }
}
