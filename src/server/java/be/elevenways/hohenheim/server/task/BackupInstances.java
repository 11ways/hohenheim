package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskContext;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Nightly instance backups (the BackupDatabases shape, riding the SAME existing
 * TaskService/claim mechanism -- deliberately NOT a third scheduling path): every
 * live instance with backups enabled and a target configured gets one export.
 *
 * AIDEV-NOTE: per-instance cron customization ("this one nightly at 4am, that one
 * hourly") is the record-schedule mechanism the instance-tier plan decided for
 * Phase 3 and nothing has built yet ({@code record_schedules} does not exist in
 * either tree). Until it lands, this daily task plus the per-instance enable flag
 * is the floor -- exactly what managed databases get.
 */
public class BackupInstances extends ScheduledTask {

    public static final String STATIC_DESCRIPTION = "Back up instances to their targets";

    @Override
    public @NonNull BackupInstances newTask() {
        return new BackupInstances();
    }

    @Override
    public @NonNull List<ScheduleDeclaration> schedules() {
        return HohenheimRoles.schedulesWhen(
            List.of(ScheduleDeclaration.fallback("0 4 * * *")),
            HohenheimRoles.Role.INSTANCES);
    }

    @Override
    public @NonNull String description() {
        return STATIC_DESCRIPTION;
    }

    @Override
    public void executor(TaskContext ctx) {
        backupAll(new InstanceBackups());
    }

    /** Export every live, backup-enabled instance; failures alert and continue. */
    public static void backupAll(InstanceBackups backups) {
        List<Row> due = Models.get(InstanceModel.class).find()
            .where(InstanceModel.DELETED_AT.isNull())
            .where(InstanceModel.BACKUP_ENABLED.eq(true))
            .all();
        int backedUp = 0;
        for (Row instance : due) {
            String name = instance.get(InstanceModel.NAME);
            try {
                backups.backupNow(instance.get(InstanceModel.ID));
                backedUp++;
            } catch (RuntimeException error) {
                String reason = error.getMessage() != null ? error.getMessage() : error.toString();
                Blast.log("TASK: BackupInstances failed for", name, ":", reason);
                try {
                    Alerts.send(NotificationEvents.BACKUP_FAILED,
                        "Instance backup failed: " + name,
                        "The scheduled backup of instance '" + name + "' failed: " + reason);
                } catch (Exception notifyError) {
                    Blast.log("TASK: could not send backup-failure notification -",
                        notifyError.getMessage());
                }
            }
        }
        Blast.log("TASK: BackupInstances backed up", backedUp, "instances");
    }
}
