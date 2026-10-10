package be.elevenways.hohenheim.server.task;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Scheduled task that dumps each running, persistent managed database through {@link DatabaseBackups}, which
 * prunes old dumps per the retention setting. Stopped and temporary (tmpfs) databases are skipped. Runs daily.
 */
public class BackupDatabases extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Back up managed databases";

    @Override
    public @NonNull Identifier id() {
        return HohenheimIds.id("backup_databases");
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

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
            String failure = DatabaseBackups.backUpOrAlert(databaseService, db.name());
            if (failure == null) {
                backedUp++;
            } else {
                failures.add(failure);
            }
        }
        Blast.log("TASK: BackupDatabases backed up", backedUp, "databases");
        return new Outcome(backedUp, failures);
    }
}
