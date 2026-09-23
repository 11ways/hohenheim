package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reports rows of the control-plane database that violate a declared foreign key, at boot
 * and daily, until an operator repairs them.
 *
 * AIDEV-NOTE: exists because enforcement came ON under a database that ran without it
 * (zenit 8a86d3c2; HohenheimDatabase keeps it on), so production may hold orphans a boot
 * log line alone would never bring to anyone. A run with findings FAILS, which is what puts
 * it on the dashboard (AttentionCollector.failedTasks projects the failed run) until a
 * clean run clears it, and the first sighting of a set of findings alerts
 * ({@link NotificationEvents#DATA_INTEGRITY}); an unchanged set does not re-alert every day.
 * Nothing here repairs anything: the offline {@code --foreign-key-orphans} command lists and,
 * per table on the operator's word, removes orphans. Node-agnostic like BackupControlPlane:
 * the control-plane database is on every node.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public class CheckForeignKeys extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Check the control-plane database for orphaned rows";

    /** The last reported summary; null while clean. In memory, so a restart re-announces. */
    private static final AtomicReference<String> LAST_REPORTED = new AtomicReference<>();

    @Override
    public @NonNull CheckForeignKeys newTask() {
        return new CheckForeignKeys();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return List.of(ScheduleDeclaration.bootAndCron("41 4 * * *"));
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        check();
    }

    /**
     * Run the check, alert on a changed finding and fail when anything is orphaned.
     *
     * @throws IllegalStateException naming every (table, parent) pair with its count
     */
    public static void check() {
        List<String> summary = HohenheimDatabase.summarize(HohenheimDatabase.foreignKeyViolations());
        if (summary.isEmpty()) {
            LAST_REPORTED.set(null);
            return;
        }
        String signature = String.join("\n", summary);
        String previous = LAST_REPORTED.getAndSet(signature);
        if (!signature.equals(previous)) {
            Blast.log("DATABASE INTEGRITY: orphaned rows found -", summary);
            Alerts.trySend(NotificationEvents.DATA_INTEGRITY,
                Microcopy.of("data_integrity_subject").withFilter("scope", "alert")
                    .withArg("count", summary.size()),
                Microcopy.of("data_integrity_body").withFilter("scope", "alert")
                    .withArg("detail", signature));
        }
        throw new IllegalStateException("Orphaned rows violate declared foreign keys (inspect and"
            + " repair offline with --foreign-key-orphans): " + String.join("; ", summary));
    }

    /**
     * Forget the transition state so a test observes the first-sighting alert.
     *
     * AIDEV-NOTE: TEST SEAM, the IsolationFindings.forgetTransitionStateForTest precedent.
     */
    public static void forgetTransitionStateForTest() {
        LAST_REPORTED.set(null);
    }
}
