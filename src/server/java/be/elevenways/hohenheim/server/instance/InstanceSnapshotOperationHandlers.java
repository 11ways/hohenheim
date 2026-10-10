package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceSnapshotOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.server.operation.OperationHandlers;

/**
 * Snapshot writes always reach the payload service, including a direct invoke outside a panel.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstanceSnapshotOperationHandlers {
    static {
        OperationHandlers.attach(InstanceSnapshotOperations.RESTORE)
            .applies(row -> InstanceSnapshotModel.STATUS_COMPLETE.equals(row.get(InstanceSnapshotModel.STATUS)))
            .authorize((row, input, access) -> HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID,
                row.get(InstanceSnapshotModel.INSTANCE_ID), HohenheimCapabilities.SNAPSHOTS) ? null
                : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "Snapshot restoration requires its instance capability"))
            .handle(call -> {
                // AIDEV-NOTE: restore records the instance-level act itself; its updateAll writes fire no save hook.
                new InstanceSnapshots().restore(call.subject().get(InstanceSnapshotModel.ID));
                return null;
            });
        OperationHandlers.attach(InstanceSnapshotOperations.DELETE)
            .authorize((row, input, access) -> HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID,
                row.get(InstanceSnapshotModel.INSTANCE_ID), HohenheimCapabilities.SNAPSHOTS) ? null
                : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "Snapshot deletion requires its instance capability"))
            .handle(call -> {
                ActivityLog.withAction(ZenitActivityAction.DELETE, "delete_snapshot",
                    () -> new InstanceSnapshots().delete(call.subject().get(InstanceSnapshotModel.ID)));
                return 1;
            });
    }
    private InstanceSnapshotOperationHandlers() {}
    public static void init() {}
}
