package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.instance.VolumeOperations.Declaration;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
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

    /** The capability on the volume's app that redeclaring and deleting a volume ask, beside the admin permission. */
    private static final String OWNER_CAPABILITY = HohenheimCapabilities.CONFIG;

    static {
        OperationHandlers.attach(VolumeOperations.CREATE).handle(call -> {
            Declaration input = Objects.requireNonNull(call.input());
            int owner = requireOwner(input.instance_id());
            HohenheimAccess.requireOperationCapability(owner, HohenheimCapabilities.CONFIG);
            Row volume = InstanceVolumes.declareOnHost(owner, Objects.requireNonNull(input.name()),
                Objects.requireNonNull(input.container_path()), VolumeOperations.quotaBytes(input.size_limit_mb()),
                Boolean.TRUE.equals(input.exclusive()));
            History.event(Models.get(InstanceModel.class), owner, Change.fact(HohenheimActivityAction.VOLUME_DECLARED,
                Map.of(HohenheimActivityAction.VOLUME_KEY, input.name())));
            return volume.get(InstanceVolumeModel.ID);
        });
        OperationHandlers.attach(VolumeOperations.UPDATE)
            .patchBase((row, access) -> declaration(Objects.requireNonNull(row)))
            .authorize((row, input, access) -> ownerRefusal(access, row.get(InstanceVolumeModel.INSTANCE_ID),
                OWNER_CAPABILITY))
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
                    VolumeOperations.quotaBytes(input.size_limit_mb()), Boolean.TRUE.equals(input.exclusive()),
                    call.expectedVersion());
                History.event(Models.get(InstanceModel.class), owner, Change.fact(
                    HohenheimActivityAction.VOLUME_REDECLARED, Map.of(HohenheimActivityAction.VOLUME_KEY, name)));
                return null;
            });
        OperationHandlers.attach(VolumeOperations.DESTROY)
            .authorize((row, input, access) -> ownerRefusal(access, row.get(InstanceVolumeModel.INSTANCE_ID),
                OWNER_CAPABILITY))
            .handle(call -> {
                destroy(call.subject());
                return null;
            });
    }

    private VolumeDeclarationOperations() {}

    public static void init() {}

    /**
     * What redeclaring or deleting ({@code operation}) refuses the caller on every volume of the app, asked before a
     * volume is looked up so a known and an unknown name answer alike.
     *
     * @return the refusal, or null when the caller may go on to name a volume
     */
    public static @Nullable DomainRefusal declarationRefusalOnApp(@NonNull Operation<Row, ?, ?> operation,
                                                                  @NonNull AccessContext access, int instanceId) {
        return refusalOnApp(operation, access, instanceId, OWNER_CAPABILITY);
    }

    /**
     * What a volume operation refuses the caller on every volume of the app: its gate's admission, then
     * {@code capability} on the app, the authority its authorizer asks of each volume.
     *
     * @return the refusal, or null when the caller may go on to name a volume
     */
    public static @Nullable DomainRefusal refusalOnApp(@NonNull Operation<Row, ?, ?> operation,
                                                       @NonNull AccessContext access, int instanceId,
                                                       @NonNull String capability) {
        if (!operation.gate().admits(access)) {
            return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "operation " + operation.id()
                + " refused at admission");
        }
        return ownerRefusal(access, instanceId, capability);
    }

    /** @return the refusal of a caller without {@code capability} on the volume's app, or null */
    public static @Nullable DomainRefusal ownerRefusal(@NonNull AccessContext access, @Nullable Integer instanceId,
                                                       @NonNull String capability) {
        return HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID, instanceId, capability) ? null
            : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "the volume's app withholds " + capability);
    }

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
        String server = HostModel.canonicalNameOf(instance.get(InstanceModel.HOST_ID));
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
