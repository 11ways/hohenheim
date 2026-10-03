package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationCommand;
import be.elevenways.zenit.common.orm.lease.LeaseKeys;
import be.elevenways.zenit.common.orm.command.CommandExecution;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The instance backup operations: restore-to-new, which the admin backup entry places on a complete row, and the
 * delete both backup entries offer as their canonical delete.
 *
 * AIDEV-NOTE: the id is the legacy row action's own, {@code hohenheim:restore_backup}. Restore-to-new creates an
 * instance OUTSIDE the creation funnel, so it is operator-only twice: its server authorizer refuses anyone else, and
 * InstanceBackups refuses a tenant-originated call underneath it. The /manage twin never places it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceBackupOperations {
    /** The subject of every backup operation: one backup row. */
    public static final SubjectType<Row> BACKUP = SubjectType.record(InstanceBackupModel.MODEL_ID);

    /** Restores the backup as a NEW instance; the source keeps running, so it confirms without a typed phrase. */
    public static final Operation<Row, Void, Restored> RESTORE_BACKUP =
        Operation.declare(HohenheimIds.id("restore_backup"))
            .label(Microcopy.of("restore_new").withFilter("scope", "instance_backup"))
            .icon(Icon.of("clone"))
            .one(BACKUP)
            .gate(OperationGate.open())
            .result(Restored.class)
            .command(OperationCommand.perSubject(LeaseKeys.declare(HohenheimIds.id("backup_command")))
                .execution(CommandExecution.OUTSIDE_TRANSACTION))
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
            .label(Microcopy.of("delete").withFilter("scope", "cms"))
            .icon(Icon.TRASH)
            .one(BACKUP)
            .gate(OperationGate.open())
            .result(Integer.class)
            .command(OperationCommand.perSubject(LeaseKeys.declare(HohenheimIds.id("backup_command")))
                .execution(CommandExecution.OUTSIDE_TRANSACTION))
            .register();

    /**
     * One restore's outcome.
     *
     * @param instanceId the new instance
     * @param missing    what could not be brought back, null when the restore was complete
     */
    @HawkeyeClass
    public record Restored(int instanceId, @Nullable String missing) {
    }

    private InstanceBackupOperations() {
    }
}
