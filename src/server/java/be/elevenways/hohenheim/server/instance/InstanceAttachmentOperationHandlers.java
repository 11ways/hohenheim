package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.instance.InstanceAttachmentOperations;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The handlers of {@link InstanceAttachmentOperations}, attached once per JVM; {@link #init()} only forces the class to
 * load before boot verifies every operation has its handler.
 *
 * AIDEV-NOTE: the authorizers are the boolean twins of the domain services' own gates, asked per rendered row through
 * the memoized walk (reachesRecord), so a surface stops offering what the service will refuse; the services keep the
 * fresh walk and stay the gate.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceAttachmentOperationHandlers {

    static {
        OperationHandlers.attach(InstanceAttachmentOperations.DETACH_DEVICE)
            .authorize(InstanceAttachmentOperationHandlers::mayDetach)
            .handle(call -> {
                Row device = call.subject();
                int instanceId = device.get(InstanceDeviceModel.INSTANCE_ID);
                String name = device.get(InstanceDeviceModel.NAME);
                ActivityLog.withAction(ZenitActivityAction.DELETE, "device_detached",
                    () -> new InstanceDevices().detach(instanceId, name));
                return 1;
            });
        OperationHandlers.attach(InstanceAttachmentOperations.DELETE_DATABASE_LINK)
            .authorize((link, input, access) -> mayChangeLink(link, access) ? null
                : new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "the attachment's two sides are not both yours"))
            .handle(call -> Models.get(InstanceDatabaseModel.class).delete(call.subject()) ? 1 : 0);
    }

    private InstanceAttachmentOperationHandlers() {
    }

    /** Loads the class, attaching the handlers; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /**
     * Edit and delete of an attachment both ride {@code TenantWrites.requireInstanceLinkAuthority}'s two-sided rule:
     * instance {@code config} plus database {@code manage}.
     */
    public static boolean mayChangeLink(@NonNull Row link, @NonNull AccessContext access) {
        Integer instanceId = link.get(InstanceDatabaseModel.INSTANCE_ID);
        Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
        return instanceId != null && databaseId != null
            && HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID, instanceId, HohenheimCapabilities.CONFIG)
            && HohenheimAccess.reachesRecord(access, DatabaseModel.MODEL_ID, databaseId, HohenheimCapabilities.MANAGE);
    }

    /** A cdrom's detach is an OPERATOR act, which the device funnel refuses anyone else with the tier refusal. */
    private static @Nullable DomainRefusal mayDetach(@NonNull Row device, @Nullable Void input,
                                                     @NonNull AccessContext access) {
        DeviceType type = DeviceType.parse(device.get(InstanceDeviceModel.TYPE));
        boolean operatorOnly = type == null || type.operatorOnly();
        if (InstanceDevices.mayChangeDevicesOf(access, device.get(InstanceDeviceModel.INSTANCE_ID))
                && (!operatorOnly || HohenheimAccess.isAdmin(access))) {
            return null;
        }
        return new DomainRefusal(ZenitRefusalReason.FORBIDDEN, "this device's detach is not yours");
    }
}
