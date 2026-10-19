package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.instance.VolumeOperations.Declaration;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * Volume operations reach the canonical declaration and host destruction services.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class VolumeDeclarationOperations {
    static {
        OperationHandlers.attach(VolumeOperations.CREATE).handle(call -> {
            Declaration input = Objects.requireNonNull(call.input());
            int owner = requireOwner(input.instance_id());
            HohenheimAccess.requireOperationCapability(owner, HohenheimCapabilities.CONFIG);
            Row volume = InstanceVolumes.declare(owner, Objects.requireNonNull(input.name()),
                Objects.requireNonNull(input.container_path()), VolumeOperations.quotaBytes(input.quota_mb()),
                Boolean.TRUE.equals(input.exclusive()));
            History.event(Models.get(InstanceModel.class), owner, Change.fact(HohenheimActivityAction.VOLUME_DECLARED,
                Map.of(HohenheimActivityAction.VOLUME_KEY, input.name())));
            return volume.get(InstanceVolumeModel.ID);
        });
        OperationHandlers.attach(VolumeOperations.UPDATE)
            .patchBase((row, access) -> declaration(Objects.requireNonNull(row)))
            .authorize((row, input, access) -> HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID,
                row.get(InstanceVolumeModel.INSTANCE_ID), HohenheimCapabilities.CONFIG) ? null
                : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "Volume owner configuration is not authorized"))
            .handle(call -> {
                Row volume = call.subject();
                Declaration input = Objects.requireNonNull(call.input());
                Integer owner = volume.get(InstanceVolumeModel.INSTANCE_ID);
                String name = volume.get(InstanceVolumeModel.NAME);
                HohenheimAccess.requireOperationCapability(owner, HohenheimCapabilities.CONFIG);
                if (!Objects.equals(name, input.name())) {
                    throw Violations.ofField("name", input.name(),
                        HohenheimMicrocopy.VIOLATIONS.of("volume_rename_unsupported"));
                }
                if (!Objects.equals(owner, input.instance_id())) {
                    throw Violations.ofField("instance_id", input.instance_id(),
                        HohenheimMicrocopy.VIOLATIONS.of("volume_rehome_unsupported"));
                }
                InstanceVolumes.declare(owner, name, Objects.requireNonNull(input.container_path()),
                    VolumeOperations.quotaBytes(input.quota_mb()), Boolean.TRUE.equals(input.exclusive()),
                    call.expectedVersion());
                History.event(Models.get(InstanceModel.class), owner, Change.fact(
                    HohenheimActivityAction.VOLUME_REDECLARED, Map.of(HohenheimActivityAction.VOLUME_KEY, name)));
                return null;
            });
        OperationHandlers.attach(VolumeOperations.DESTROY).handle(call -> {
            destroy(call.subject());
            return null;
        });
    }

    private VolumeDeclarationOperations() {}

    public static void init() {}

    public static @NonNull Declaration declaration(@NonNull Row row) {
        return new Declaration(row.get(InstanceVolumeModel.INSTANCE_ID), row.get(InstanceVolumeModel.NAME),
            row.get(InstanceVolumeModel.CONTAINER_PATH),
            VolumeOperations.quotaMegabytes(row.get(InstanceVolumeModel.QUOTA_BYTES)),
            row.get(InstanceVolumeModel.EXCLUSIVE));
    }

    public static void destroy(@NonNull Row volume) {
        Integer owner = volume.get(InstanceVolumeModel.INSTANCE_ID);
        String name = volume.get(InstanceVolumeModel.NAME);
        // Trashed owners still locate the real host; absence must not re-point a surviving directory locally.
        Row instance = StoredRows.byId(Models.get(InstanceModel.class), owner);
        if (instance == null) {
            throw new DomainRefusal(ZenitRefusalReason.NOT_FOUND, "Volume owner no longer exists");
        }
        if (instance != null && WorkspaceKind.ID.toString().equals(instance.get(InstanceModel.KIND))
                && WorkspaceKind.HOME_VOLUME.equals(name)) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("volume_home_protected"));
        }
        String server = ServerModel.canonicalNameOf(instance.get(InstanceModel.SERVER_ID));
        InstanceVolumes.destroyOne(owner, name, server);
    }

    private static int requireOwner(@Nullable Integer owner) {
        Row row = Models.get(InstanceModel.class).findById(owner);
        if (row == null) throw Violations.ofField("instance_id", owner,
                HohenheimMicrocopy.VIOLATIONS.of("unknown_instance"));
        InstanceKindHandler handler = InstanceKinds.handlerOf(row);
        if (handler == null || !handler.supportsVolumes()) {
            throw Violations.ofField("instance_id", owner, HohenheimMicrocopy.VIOLATIONS.of("volume_kind_unsupported"));
        }
        return owner;
    }
}
