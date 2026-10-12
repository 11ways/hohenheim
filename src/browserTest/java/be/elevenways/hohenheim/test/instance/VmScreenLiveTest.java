package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.protoblast.common.input.KeyCode;
import be.elevenways.protoblast.common.time.Now;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A VM's screen end to end against a REAL VM on a live Incus host, through the production screen socket and the
 * real SPICE path (Incus VGA console, one websocket per SPICE channel, Pepperglass): a granted non-admin tenant sees
 * the VM's screen and drives it, and once the grant is revoked the next revalidation closes the view. The VM's
 * template declares a readiness line and a stop command, whose serial console is let go once the line is seen, so the
 * screen opens without a take-over. Skips unless a live Incus host is enrolled.
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
        int templateId = consoleTemplate();
        int instanceId = vmRecord("vm-screen-probe", hostId, templateId);
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.CONSOLE, true);
        InstanceService service = new InstanceService();
        RecordingScreenSocket viewer = null;
        try {
            assertThat(service.deploy(instanceId).state()).as("the VM deploys and runs")
                .isEqualTo(ContainerState.RUNNING);
            // 0. Its serial console shows the readiness line, and the record runs; the console that watched for the
            //    line is let go then, though the template also declares a stop command.
            Poll.until("step 0: the readiness line was seen", Duration.ofMinutes(5), Duration.ofSeconds(2),
                () -> InstanceModel.STATUS_RUNNING.equals(
                    Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS)));
            Poll.until("step 0: the readiness watch let the console go", Duration.ofSeconds(20),
                () -> InstanceConsoles.peek(instanceId) == null);
            // 1. The tenant's viewer controls the screen, never held by the serial watch, and is drawn a frame of the
            //    VM's SPICE display.
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
                HohenheimCapabilities.CONSOLE);
            assertThat(viewer.handler().revalidate()).as("step 3: a revoked tenant is refused").isFalse();
            viewer.disconnect();
            viewer = null;

            // 4. Stopping it sends the stop command over a console opened for it alone (or leaves the stop to the
            //    daemon while the screen still holds it); the VM stops and no console session is kept.
            service.stop(instanceId);
            assertThat((String) Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS))
                .as("step 4: the VM stopped").isEqualTo(InstanceModel.STATUS_STOPPED);
            assertThat(InstanceConsoles.peek(instanceId)).as("step 4: no console session is kept").isNull();
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
                HohenheimCapabilities.CONSOLE);
            HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
            HardDeletes.byId(Models.get(InstanceTemplateModel.class), templateId);
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

    /** A VM template declaring the readiness line Alpine's cloud image prints on its serial console, and a stop. */
    private static int consoleTemplate() {
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, "vm-screen-console-template");
        row.set(InstanceTemplateModel.DESCRIPTION, "readiness and stop over the serial console");
        row.set(InstanceTemplateModel.KIND, "hohenheim:vm");
        row.set(InstanceTemplateModel.VERSION, 1);
        row.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        row.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.CONSOLE_LINE.token());
        row.set(InstanceTemplateModel.READINESS_LINE, "Welcome to Alpine Linux");
        row.set(InstanceTemplateModel.STOP_COMMAND, "poweroff");
        Models.get(InstanceTemplateModel.class).save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static int vmRecord(String name, int hostId, int templateId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", VM_IMAGE, "memory_limit_mb", 512)));
        row.set(InstanceModel.SERVER_ID, hostId);
        row.set(InstanceModel.TEMPLATE_ID, templateId);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }
}
