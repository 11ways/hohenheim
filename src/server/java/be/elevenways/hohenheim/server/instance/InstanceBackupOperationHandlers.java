package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.instance.InstanceBackupOperations.RestoreInput;
import be.elevenways.hohenheim.instance.InstanceBackupOperations.Restored;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.InputScope;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            .inputScope(InstanceBackupOperationHandlers::destinations)
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
     * The restore's host pick, scoped to this backup: every host, those the restore's gate refuses shown disabled
     * with the gate's own words (the Migrate page's shape), blank left to placement.
     */
    private static @NonNull InputScope destinations(@Nullable Row backup, @NonNull FormSpec declared,
                                                    @Nullable AccessContext access) {
        List<FieldOption<Integer>> options = new ArrayList<>();
        if (backup != null) {
            for (InstanceBackups.Destination destination : InstanceBackups.destinationsFor(backup)) {
                FieldOption<Integer> option = FieldOption.of(destination.serverId(),
                    Microcopy.literal(destination.name()));
                options.add(destination.refusal() == null ? option
                    : option.asDisabled().withDescription(destination.refusal()));
            }
        }
        return InputScope.of(InstanceTemplateHandlers.replacing(declared, Map.of(
            InstanceTemplateOperations.SERVER_ID.getName(),
            Select.of(InstanceTemplateOperations.SERVER_ID).options(OptionSource.of(options)).build())));
    }

    /**
     * AIDEV-NOTE: the activity wrapper is NOT vacuous: restoreToNew ends in a real InstanceModel.save() of the new
     * record, so a create hook fires inside and there is a row for this name to rename.
     */
    private static @NonNull Restored restore(@NonNull OperationCall<Row, RestoreInput> call) {
        AtomicReference<InstanceBackups.Restored> restored = new AtomicReference<>();
        Integer serverId = call.input() == null ? null : call.input().serverId();
        ActivityLog.withAction(ZenitActivityAction.CREATE, "restore_backup",
            () -> restored.set(new InstanceBackups().restoreToNew(call.subject().get(InstanceBackupModel.ID),
                null, serverId)));
        InstanceBackups.Restored outcome = restored.get();
        return new Restored(outcome.instanceId(), outcome.complete() ? null : outcome.describeLosses());
    }
}
