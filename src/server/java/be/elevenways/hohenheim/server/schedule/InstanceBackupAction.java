package be.elevenways.hohenheim.server.schedule;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.task.record.RecordScheduleActionContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Scheduled off-host backup of one instance to its configured target (the successor
 * of the retired nightly BackupInstances sweep). Failures alert like the old task did,
 * AND still fail the step so the run records them.
 */
public class InstanceBackupAction extends InstanceScheduleAction {

    public static final Identifier ID = Identifier.of("hohenheim", "backup");

    @Override public @NonNull Identifier typeId() { return ID; }
    @Override public @NonNull String getDisplayName() { return "Backup"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("backup").withFilter("scope", "schedule_action");
    }

    @Override public @Nullable Schema getSchema() { return null; }
    @Override public @Nullable Icon getIcon() { return Icon.of("box-archive"); }

    @Override
    public @NonNull String requiredCapability() {
        return HohenheimAccess.BACKUPS;
    }

    @Override
    public void execute(@NonNull RecordScheduleActionContext context) {
        int instanceId = context.recordIdAsInt();

        try {
            new InstanceBackups().backupNow(instanceId);
        } catch (RuntimeException error) {
            this.alert(instanceId, error);
            throw error;
        }
    }

    /** Operator-alert parity with the retired nightly task; never masks the failure. */
    private void alert(int instanceId, @NonNull RuntimeException error) {
        String reason = error.getMessage() != null ? error.getMessage() : error.toString();
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        String name = instance != null ? instance.get(InstanceModel.NAME) : ("#" + instanceId);

        try {
            Alerts.send(NotificationEvents.BACKUP_FAILED,
                    "Instance backup failed: " + name,
                    "The scheduled backup of instance '" + name + "' failed: " + reason);
        } catch (Exception notifyError) {
            Blast.log("SCHEDULE: could not send backup-failure notification -",
                    notifyError.getMessage());
        }
    }
}
