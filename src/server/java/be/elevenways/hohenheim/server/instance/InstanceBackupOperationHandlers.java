package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.instance.InstanceBackupOperations.RestoreInput;
import be.elevenways.hohenheim.instance.InstanceBackupOperations.Restored;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceTemplateOperations;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.Authorizer;
import be.elevenways.zenit.server.operation.InputScope;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The handlers of both restores and the backup delete, attached once per JVM; {@link #init()} only forces the class
 * to load before boot verifies every operation has its handler.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceBackupOperationHandlers {

    static {
        OperationHandlers.attach(InstanceBackupOperations.RESTORE_BACKUP)
            .applies(InstanceBackupModel::isRestorable)
            .inputScope(InstanceBackupOperationHandlers::destinations)
            .authorize(onBackupInstance(HohenheimCapabilities.BACKUPS))
            .handle(call -> refusedInWords(() -> restore(call)));
        OperationHandlers.attach(InstanceBackupOperations.RESTORE_BACKUP_IN_PLACE)
            .applies(backup -> InstanceBackupModel.isRestorable(backup)
                && backup.get(InstanceBackupModel.INSTANCE_ID) != null && InstanceBackups.restorableInPlace(backup))
            .authorize(onBackupInstance(HohenheimCapabilities.DESTROY, HohenheimCapabilities.BACKUPS))
            .handle(call -> refusedInWords(() -> restoreInPlace(call)));
        OperationHandlers.attach(InstanceBackupOperations.DELETE_BACKUP)
            .handle(InstanceBackupOperationHandlers::delete);
    }

    private InstanceBackupOperationHandlers() {
    }

    /**
     * Run a restore's body, its service's {@code Violations} answered as a refusal carrying the first violation's
     * words: a background run keeps a refusal's words, while any other exception ends it as a generic failure.
     *
     * AIDEV-NOTE: the restore service speaks Violations to its synchronous callers (the API, the tests), so the
     * operation edge is where they become the run's refusal. Core's run lane keeps no words for a Violations of its own;
     * if it ever does, this goes.
     */
    private static <T> T refusedInWords(@NonNull Supplier<T> body) {
        try {
            return body.get();
        } catch (Violations refused) {
            List<Violation> all = refused.all();
            throw new DomainRefusal(HohenheimRefusalReason.BACKUP_RESTORE_REFUSED, refused.getMessage(),
                all.isEmpty() ? null : all.getFirst().message());
        }
    }

    /** Loads the class, attaching the handler; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /**
     * Admits an operator, or a caller holding every one of {@code capabilities} on the backup's instance; anyone else
     * is refused with the instance tier's uniform refusal, so no surface becomes a capability oracle.
     *
     * AIDEV-NOTE: the service asks again underneath (InstanceBackups' tenant gates, requireDestroyPermitted), so an
     * entry that reaches it without this pipeline is gated the same.
     */
    private static <I> @NonNull Authorizer<Row, I> onBackupInstance(@NonNull String... capabilities) {
        return (backup, input, access) -> {
            if (HohenheimAccess.isAdmin(access)) {
                return null;
            }
            Integer instanceId = backup.get(InstanceBackupModel.INSTANCE_ID);
            for (String capability : capabilities) {
                if (!HohenheimAccess.hasInstanceCapability(access, instanceId, capability)) {
                    return new DomainRefusal(HohenheimRefusalReason.INSTANCE_NOT_PERMITTED, "restoring backup "
                        + backup.get(InstanceBackupModel.ID) + " needs " + capability + " on its instance");
                }
            }
            return null;
        };
    }

    /** Removes the artifact from its target and the row; the activity row names the act. */
    private static @NonNull Integer delete(@NonNull OperationCall<Row, Void> call) {
        Integer id = call.subject().get(InstanceBackupModel.ID);
        new InstanceBackups().delete(id);
        return 1;
    }

    /**
     * The restore's host pick, scoped to this backup: every host, those the restore's gate refuses shown disabled
     * with the gate's own words (the Migrate page's shape), blank left to placement. A tenant is offered no host, as
     * on the create form: its restore lands on the instance's own host whatever it submits.
     */
    private static @NonNull InputScope destinations(@Nullable Row backup, @NonNull FormSpec declared,
                                                    @Nullable AccessContext access) {
        List<FieldOption<Integer>> options = new ArrayList<>();
        if (backup != null && access != null && HohenheimAccess.isAdmin(access)) {
            for (InstanceBackups.Destination destination : InstanceBackups.destinationsFor(backup)) {
                FieldOption<Integer> option = FieldOption.of(destination.serverId(),
                    Microcopy.literal(destination.name()));
                options.add(destination.refusal() == null ? option
                    : option.disabledBecause(destination.refusal()));
            }
        }
        return InputScope.of(InstanceTemplateHandlers.replacing(declared, Map.of(
            InstanceTemplateOperations.SERVER_ID.getName(),
            Select.of(InstanceTemplateOperations.SERVER_ID).options(OptionSource.of(options)).build())));
    }

    /** Restores to a new instance; the run leads to it and its step says what did not come back. */
    private static @NonNull Restored restore(@NonNull OperationCall<Row, RestoreInput> call) {
        Integer serverId = call.input() == null ? null : call.input().serverId();
        InstanceBackups.Restored outcome = new InstanceBackups().restoreToNew(
            call.subject().get(InstanceBackupModel.ID), null, serverId);
        Row created = Models.get(InstanceModel.class).findById(outcome.instanceId());
        if (created != null) {
            call.attachSubject(InstanceOperations.INSTANCE, created);
        }
        Restored restored = new Restored(outcome.instanceId(), outcome.complete() ? null : outcome.describeLosses(),
            outcome.movedPorts().isEmpty() ? null : String.join(", ", outcome.movedPorts()));
        call.reportStepDone(told(restored));
        return restored;
    }

    /** The words a finished restore-to-new leaves on its run: the new instance, then what did not come back. */
    private static @NonNull Microcopy told(@NonNull Restored restored) {
        Microcopy told = restored.missing() != null
            ? HohenheimMicrocopy.INSTANCE_BACKUP.of("restored_new_partial")
                .withArg("id", restored.instanceId())
                .withArg("missing", restored.missing())
            : HohenheimMicrocopy.INSTANCE_BACKUP.of("restored_new").withArg("id", restored.instanceId());
        return restored.movedPorts() == null ? told
            : HohenheimMicrocopy.INSTANCE_BACKUP.of("restored_new_ports_moved").withArg("told", told)
                .withArg("ports", restored.movedPorts());
    }

    /** Backs the instance up, then restores the backup onto it; the step names the backup taken first. */
    private static @NonNull Integer restoreInPlace(@NonNull OperationCall<Row, Void> call) {
        Row backup = call.subject();
        int before = new InstanceBackups().restoreInPlace(backup.get(InstanceBackupModel.ID));
        Row taken = Models.get(InstanceBackupModel.class).findById(before);
        call.reportStepDone(HohenheimMicrocopy.INSTANCE_BACKUP.of("restored_in_place")
            .withArg("at", InstanceBackupModel.title(backup))
            .withArg("before", taken != null ? InstanceBackupModel.title(taken) : Microcopy.literal("#" + before)));
        return before;
    }
}
