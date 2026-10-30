package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.zenit.cms.common.CmsMicrocopy;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.operation.OperationInput;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.OperationSteps;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The instance backup operations: restore-to-new and restore-in-place, which both backup entries place on a complete
 * row, and the delete both offer as their canonical delete.
 *
 * AIDEV-NOTE: the id of restore-to-new is the legacy row action's own, {@code hohenheim:restore_backup}. Both restores
 * are background runs that spend the deploy lane's {@code hh_deploy} budget, and ONE writer serves the operator and the
 * tenant (InstanceBackups): a tenant's restore-to-new needs BACKUPS on the instance and passes the create funnel's
 * gates there (create authority, the approved template, the instance's own host and environment, the owner's quota),
 * and restore-in-place needs DESTROY and BACKUPS on it and always takes a fresh backup first.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceBackupOperations {
    private static final OperationCommand COMMAND = OperationCommand.perSubject()
        .execution(CommandExecution.OUTSIDE_TRANSACTION);

    /** The subject of every backup operation: one backup row. */
    public static final SubjectType<Row> BACKUP = SubjectType.record(InstanceBackupModel.REF);

    /**
     * Restores the backup as a NEW instance on the host the operator picks, or the one placement picks when none is;
     * a tenant's lands on its instance's own host. The source keeps running, so it confirms without a typed phrase.
     *
     * AIDEV-NOTE: the host pick reuses the create forms' own field (InstanceTemplateOperations.SERVER_ID, whose help
     * says what blank means). Restoring onto ANOTHER host is the recovery for a dead one, so blank never means "the
     * source host" for an operator: the server scopes the pick to the placement gate's verdict per host, and offers a
     * tenant no pick at all.
     */
    public static final Operation<Row, RestoreInput, Restored> RESTORE_BACKUP =
        Operation.declare(HohenheimIds.id("restore_backup"))
            .happened(OperationSentences.of("restore_backup"))
            .label(HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_new"))
            .icon(Icon.of("clone"))
            .one(BACKUP)
            .gate(OperationGate.open())
            .input(OperationInput.of(FormSpec.builder().add(InstanceTemplateOperations.SERVER_ID).build(),
                RestoreInput.class, values -> new RestoreInput(values.get(InstanceTemplateOperations.SERVER_ID))))
            .result(Restored.class)
            .command(COMMAND)
            .rateLimit(HohenheimEndpoints.DEPLOY_LIMIT)
            .background(OperationSteps.of(
                OperationSteps.step("restore", HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_new_step"))))
            .register();

    /** Where a restore lands: a host, or null to let placement pick one. */
    public record RestoreInput(@Nullable Integer serverId) {
    }

    /**
     * Takes a fresh backup of the backup's instance, then replaces the instance's data with the backup's; answers the
     * fresh backup's id.
     */
    public static final Operation<Row, Void, Integer> RESTORE_BACKUP_IN_PLACE =
        Operation.declare(HohenheimIds.id("restore_backup_in_place"))
            .happened(OperationSentences.of("restore_backup_in_place"))
            .label(HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_in_place"))
            .icon(Icon.of("clock-rotate-left"))
            .one(BACKUP)
            .gate(OperationGate.open())
            .facts(OperationFact.DESTRUCTIVE, OperationFact.REACHES_OUTSIDE)
            .result(Integer.class)
            .command(COMMAND)
            .rateLimit(HohenheimEndpoints.DEPLOY_LIMIT)
            .background(OperationSteps.of(
                OperationSteps.step("restore", HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_in_place_step"))))
            .register();

    /**
     * Removes the backup's artifact FROM ITS TARGET with the row, and refuses when the target cannot be reached.
     *
     * AIDEV-NOTE: a domain delete, never the plain row delete: the row is the only index of an artifact stored
     * elsewhere, so deleting the row alone would orphan it. Its authority is BACKUPS on the backup's instance, which
     * InstanceBackups.delete asks itself; the entries' scopes decide which rows a caller reaches at all.
     */
    public static final Operation<Row, Void, Integer> DELETE_BACKUP =
        Operation.declare(HohenheimIds.id("delete_backup"))
            .happened(OperationSentences.of("delete_backup"))
            .label(CmsMicrocopy.of("delete"))
            .icon(Icon.TRASH)
            .one(BACKUP)
            .gate(OperationGate.open())
            .result(Integer.class)
            .command(COMMAND)
            .register();

    /**
     * One restore-to-new's outcome.
     *
     * @param instanceId the new instance
     * @param missing    what could not be brought back, null when the restore was complete
     * @param movedPorts the fixed host ports the target host already held, now ones it picks ("game 30000/tcp"), null
     *                   when every port stayed as it was
     */
    @HawkeyeClass
    public record Restored(int instanceId, @Nullable String missing, @Nullable String movedPorts) {
    }

    private InstanceBackupOperations() {
    }
}
