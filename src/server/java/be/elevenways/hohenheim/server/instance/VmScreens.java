package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.kvm.server.ScreenAccess;
import be.elevenways.zenit.kvm.server.ScreenEndpoint;
import be.elevenways.zenit.kvm.server.ScreenOptions;
import be.elevenways.zenit.kvm.server.ScreenSession;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The screens of virtual machines on the console's screen socket: one zenit-kvm session per running VM, opened when its
 * first viewer arrives and ended when its last one leaves, fed by the VM's SPICE server.
 *
 * AIDEV-NOTE: the gate is the open-framebuffer operation's offer, which the screen mode's tab asks too: its holders
 * (CONSOLE, which MANAGE implies) see and may drive the screen, anyone else is closed 1008 at open and on the next
 * revalidation. Who drives at a given moment is the session's call: the first viewer, until it hands control over.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class VmScreens {

    private static final Map<Integer, ScreenSession> LIVE = new HashMap<>();

    private VmScreens() {
    }

    /** Puts the screens on their socket; called once while the handlers are installed. */
    public static void install() {
        ScreenEndpoint.on(HohenheimEndpoints.VM_FRAMEBUFFER,
                socket -> socket.getParameter(HohenheimEndpoints.INSTANCE_ID))
            .gate((principal, instanceId) -> InstanceOperationHandlers.offered(InstanceOperations.OPEN_FRAMEBUFFER,
                principal, instanceId) ? ScreenAccess.CONTROL : ScreenAccess.NONE)
            .sessions(VmScreens::session)
            .install();
    }

    /** @return the running VM's screen session, opened for its first viewer; null while the VM is not running */
    private static @Nullable ScreenSession session(@NonNull Integer instanceId) {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        String status = instance == null ? null : instance.get(InstanceModel.STATUS);
        if (!InstanceModel.STATUS_RUNNING.equals(status) && !InstanceModel.STATUS_STARTING.equals(status)) {
            return null;
        }
        String serverName = ServerModel.nameOf(instance.get(InstanceModel.SERVER_ID));
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        ScreenSession opened;
        synchronized (LIVE) {
            ScreenSession live = LIVE.get(instanceId);
            if (live != null && !live.isEnded()) {
                return live;
            }
            opened = ScreenSession.open(new SpiceScreenSource(() -> VmSpice.SEAM.require().connect(serverName,
                handle)), ScreenOptions.DEFAULTS.endingWhenUnwatched());
            LIVE.put(instanceId, opened);
        }
        opened.onEnd(() -> {
            synchronized (LIVE) {
                LIVE.remove(instanceId, opened);
            }
        });
        return opened;
    }
}
