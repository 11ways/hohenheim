package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.protoblast.common.input.KeyCode;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.kvm.common.ScreenMessage;
import be.elevenways.zenit.kvm.common.ScreenRole;
import be.elevenways.zenit.kvm.test.support.RecordingScreenSocket;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VM's screen end to end against a REAL VM on a live Incus host, through the production screen socket and the
 * real SPICE path (Incus VGA console, one websocket per SPICE channel, Pepperglass): a granted non-admin tenant sees
 * the VM's screen and drives it, and once the grant is revoked the next revalidation closes the view. Skips unless a
 * live Incus host is enrolled.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class VmScreenLiveTest extends HohenheimTestBase {

    private static final String HOST = "live-incus-screen";
    private static final String VM_IMAGE = "alpine/3.22/cloud";

    @Test
    void aTenantSeesAndDrivesARealVmsScreenUntilTheGrantIsRevoked() throws Exception {
        LiveIncusHost remote = LiveIncusHost.requirePrimary();
        String enrolledFingerprint = remote.enrollThroughProduct(HOST, "hohenheim-live-screen");
        int hostId = Models.get(ServerModel.class).findByName(HOST).get(ServerModel.ID);
        int userId = ApiSupport.user("screen-live@hohenheim.local", "Screen live");
        int instanceId = vmRecord("vm-screen-probe", hostId);
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONSOLE, true);
        InstanceService service = new InstanceService();
        RecordingScreenSocket viewer = null;
        try {
            assertThat(service.deploy(instanceId).state()).as("the VM deploys and runs")
                .isEqualTo(ContainerState.RUNNING);

            // 1. The tenant's viewer controls the screen and is drawn a frame of the VM's SPICE display, before
            //    the guest agent is up.
            viewer = RecordingScreenSocket.as(new UserPrincipal(userId, "Screen live")).autoAck(true)
                .with(HohenheimEndpoints.INSTANCE_ID, instanceId).open(HohenheimEndpoints.VM_FRAMEBUFFER);
            viewer.await(ScreenMessage.Role.class, role -> role.role() == ScreenRole.CONTROLLER);
            viewer.await(ScreenMessage.FrameEnd.class, end -> true);
            assertThat(viewer.picture().width()).as("step 1: the VM's screen has a size").isPositive();

            // 2. A key goes to the guest over the live SPICE inputs channel without ending the screen.
            viewer.send(new ScreenMessage.Key(KeyCode.KEY_R, "r", true));
            viewer.send(new ScreenMessage.Key(KeyCode.KEY_R, "r", false));
            assertThat(viewer.closeCode()).as("step 2: input did not end the screen").isEqualTo(-1);

            // 3. Revoked, the next revalidation refuses the tenant, which zenit answers with a 1008 close.
            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.CONSOLE);
            assertThat(viewer.handler().revalidate()).as("step 3: a revoked tenant is refused").isFalse();
        } finally {
            if (viewer != null) {
                viewer.disconnect();
            }
            try {
                service.destroy(instanceId);
            } catch (RuntimeException ignored) {
                // best effort; forceDelete below is the daemon-truth cleanup
            }
            remote.forceDelete(handle);
            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.CONSOLE);
            HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
            Models.get(UserModel.class).delete(userId);
            try {
                remote.releaseControllerSharedObjects();
                remote.releaseAuthorizedKeys();
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    private static int vmRecord(String name, int hostId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", VM_IMAGE, "memory_limit_mb", 512)));
        row.set(InstanceModel.SERVER_ID, hostId);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
