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

    public static final String STATIC_DESCRIPTION = "Back up the control-plane database and keyring";

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
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        try {
            ControlPlaneBackups.backupNow();
        } catch (Exception error) {
            Blast.log("TASK: BackupControlPlane failed:", error.getMessage());
            Alerts.trySend(NotificationEvents.BACKUP_FAILED, Alerts.INSTALLATION,
                HohenheimMicrocopy.ALERT.of("control_plane_backup_failed_subject"),
                HohenheimMicrocopy.ALERT.of("control_plane_backup_failed_body")
                    .withArg("reason", String.valueOf(error.getMessage())));
            // Rethrow so the task run is recorded as FAILED, not green-with-a-log-line.
            throw error instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("Control-plane backup failed", error);
        }
    }
}
