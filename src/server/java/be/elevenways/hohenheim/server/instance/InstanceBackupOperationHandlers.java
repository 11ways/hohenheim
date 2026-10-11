package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.instance.InstanceBackupOperations.Restored;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The handlers of restore-to-new and the backup delete, attached once per JVM; {@link #init()} only forces the class to
 * load before boot verifies every operation has its handler.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceBackupOperationHandlers {

    static {
        OperationHandlers.attach(InstanceBackupOperations.RESTORE_BACKUP)
            .applies(backup -> InstanceBackupModel.STATUS_COMPLETE.equals(backup.get(InstanceBackupModel.STATUS)))
            .authorize(HohenheimAccess.operatorOnly("restore-to-new is an operator act"))
            .handle(InstanceBackupOperationHandlers::restore);
        OperationHandlers.attach(InstanceBackupOperations.DELETE_BACKUP)
            .handle(InstanceBackupOperationHandlers::delete);
    }

    private InstanceBackupOperationHandlers() {
    }

    /** Loads the class, attaching the handler; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /** Removes the artifact from its target and the row; the activity row names the act. */
    private static @NonNull Integer delete(@NonNull OperationCall<Row, Void> call) {
        Integer id = call.subject().get(InstanceBackupModel.ID);
        ActivityLog.withAction(ZenitActivityAction.DELETE, "delete_backup", () -> new InstanceBackups().delete(id));
        return 1;
    }

    /**
     * AIDEV-NOTE: the activity wrapper is NOT vacuous: restoreToNew ends in a real InstanceModel.save() of the new
     * record, so a create hook fires inside and there is a row for this name to rename.
     */
    private static @NonNull Restored restore(@NonNull OperationCall<Row, Void> call) {
        AtomicReference<InstanceBackups.Restored> restored = new AtomicReference<>();
        ActivityLog.withAction(ZenitActivityAction.CREATE, "restore_backup",
            () -> restored.set(new InstanceBackups().restoreToNew(call.subject().get(InstanceBackupModel.ID),
                null, null)));
        InstanceBackups.Restored outcome = restored.get();
        return new Restored(outcome.instanceId(), outcome.complete() ? null : outcome.describeLosses());
    }
}
