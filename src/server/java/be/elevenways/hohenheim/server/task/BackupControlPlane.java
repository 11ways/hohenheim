package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.server.database.ControlPlaneBackups;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Scheduled control-plane recovery backup: hohenheim's own database plus the field-encryption
 * keyring as ONE verified archive (see {@link ControlPlaneBackups}). Deliberately role-FREE:
 * the control-plane database exists on every node regardless of which roles it runs, so unlike
 * {@link BackupDatabases} (tenant databases, DATABASES role) this never gates on a role.
 * A failure is alerted, never just logged -- a backup that silently stops happening is the
 * quiet twin of a backup that silently does not restore.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public class BackupControlPlane extends ScheduledTask {

    /** The task's catalog id, also the {@code system_task_history.task_type} its runs are stored under. */
    public static final Identifier ID = HohenheimIds.id("backup_control_plane");

    @Override
    public @NonNull Identifier id() {
        return ID;
    }

    @Override
    public @NonNull Microcopy label() {
        return this.labelIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    @Override
    public @NonNull BackupControlPlane newTask() {
        return new BackupControlPlane();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        // 02:30 UTC: before the 03:00 tenant dumps, so both land in the same nightly window.
        return List.of(ScheduleDeclaration.fallback("30 2 * * *"));
    }

    @Override
    public @NonNull Microcopy description() {
        return this.descriptionIn(HohenheimMicrocopy.HOHENHEIM.copy());
    }

    /**
     * AIDEV-NOTE: a backup with no destination chosen is NOT SET UP, not a failure: no alert, and the run's refusal
     * folds on the dashboard under the item saying where to choose one (AttentionCollector.failedTasks). A real failure
     * is alerted and stored as a sentence naming the target; the exception's own text (setting paths, target lists,
     * transport errors) goes to the log and the alert only, never to the run's stored reason the dashboard reads.
     */
    @Override
    public void executor(TaskContext ctx) {
        String target = ControlPlaneBackups.configuredDestinationName();
        if (target == null) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("control_plane_backup_not_set_up"));
        }
        try {
            ControlPlaneBackups.backupNow();
        } catch (Exception error) {
            Blast.log("TASK: BackupControlPlane failed:", error.getMessage());
            Alerts.trySend(NotificationEvents.BACKUP_FAILED, Alerts.INSTALLATION,
                HohenheimMicrocopy.ALERT.of("control_plane_backup_failed_subject"),
                HohenheimMicrocopy.ALERT.of("control_plane_backup_failed_body")
                    .withArg("reason", String.valueOf(error.getMessage())));
            // Rethrow so the task run is recorded as FAILED, not green-with-a-log-line.
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("control_plane_backup_failed")
                .withArg("target", target));
        }
    }
}
