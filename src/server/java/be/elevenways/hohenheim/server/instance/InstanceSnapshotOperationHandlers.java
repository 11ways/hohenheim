package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.InstanceSnapshotOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationHandlers;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Snapshot writes always reach the payload service, including a direct invoke outside a panel.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstanceSnapshotOperationHandlers {
    static {
        OperationHandlers.attach(InstanceSnapshotOperations.RESTORE)
            .applies(InstanceSnapshotModel::isRestorable)
            .authorize((row, input, access) -> reachesApp(row, access, "Snapshot restoration"))
            .handle(call -> {
                // AIDEV-NOTE: restore records the instance-level act itself; its updateAll writes fire no save hook.
                new InstanceSnapshots().restore(call.subject().get(InstanceSnapshotModel.ID));
                return null;
            });
        OperationHandlers.attach(InstanceSnapshotOperations.DELETE)
            .authorize((row, input, access) -> reachesApp(row, access, "Snapshot deletion"))
            .handle(call -> {
                new InstanceSnapshots().delete(call.subject().get(InstanceSnapshotModel.ID));
                return 1;
            });
        OperationHandlers.attach(InstanceSnapshotOperations.CHANGE_NOTE)
            .patchBase((row, access) -> new InstanceSnapshotOperations.NoteInput(
                Objects.requireNonNull(row).get(InstanceSnapshotModel.NOTE)))
            .authorize((row, input, access) -> reachesApp(row, access, "A snapshot's note"))
            .handle(call -> {
                Row snapshot = call.subject();
                String note = Objects.requireNonNull(call.input(), "a note change carries its input").note();
                snapshot.set(InstanceSnapshotModel.NOTE, note == null || note.isBlank() ? null : note);
                Models.get(InstanceSnapshotModel.class).save(snapshot);
                return snapshot.get(InstanceSnapshotModel.ID);
            });
    }

    /** @return the refusal of a caller without the snapshot capability on the snapshot's app, null for one with it */
    private static @Nullable DomainRefusal reachesApp(@NonNull Row snapshot, @NonNull AccessContext access,
                                                      @NonNull String act) {
        return HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID,
            snapshot.get(InstanceSnapshotModel.INSTANCE_ID), HohenheimCapabilities.SNAPSHOTS) ? null
            : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, act + " requires its instance capability");
    }
    private InstanceSnapshotOperationHandlers() {}
    public static void init() {}
}
