package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.LiveIncusHost;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.session.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The framebuffer rescue console end to end against a REAL VM on daystrom, over the REAL
 * production endpoint and a REAL socket: a granted non-admin tenant connects to a running
 * VM's VGA console, framebuffer bytes flow (a snapshot frame arrives), keyboard input is
 * carried to the guest over a live SPICE input channel, and then the MANAGE grant is
 * revoked and the tenant's OPEN console is DISCONNECTED with 1008 -- not merely refused on
 * a later connect. Skips unless a live Incus host is enrolled.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class VmFramebufferConsoleLiveTest extends HohenheimTestBase {

    private static final String HOST = "live-incus-framebuffer";
    private static final String VM_IMAGE = "alpine/3.22/cloud";

    @Test
    void framebufferConsoleAgainstARealVmAndRevocationClosesIt1008() throws Exception {
        LiveIncusHost remote = LiveIncusHost.configured();
        LiveLane.require(LiveLane.Need.INCUS_HOST, remote != null,
            "no live incus host enrolled at " + LiveIncusHost.CONFIG);

        String enrolledFingerprint = remote.enrollThroughProduct(HOST, "hohenheim-live-fb");
        Row host = Models.get(ServerModel.class).findByName(HOST);
        int hostId = host.get(ServerModel.ID);

        int userId = user("fb-live");
        int instanceId = vmRecord("vm-fb-probe", hostId);
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        String token = sessionFor(userId).token();

        InstanceService service = new InstanceService();
        WebSocket ws = null;
        try {
            assertThat(service.deploy(instanceId).state())
                .as("the VM deploys and runs").isEqualTo(ContainerState.RUNNING);

            RecordingClient client = new RecordingClient();
            ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + token)
                .buildAsync(URI.create("ws://localhost:" + getServerPort()
                    + "/ws/instance-framebuffer/" + instanceId), client)
                .join();

            // Clause 1: framebuffer bytes flow -- a real VGA PNG snapshot arrives, and it
            // works BEFORE the guest agent is up (the rescue console's whole point).
            assertThat(client.binaryArrived.await(30, TimeUnit.SECONDS))
                .as("a real VGA framebuffer snapshot reaches the tenant").isTrue();
            byte[] frame = client.lastBinary.get();
            assertThat(frame).isNotNull().isNotEmpty();
            assertThat(new String(frame, 1, 3, java.nio.charset.StandardCharsets.US_ASCII))
                .as("the snapshot is a PNG image").isEqualTo("PNG");

            // Clause 1: input is carried to the guest over the live SPICE input channel
            // (the scancode round-trips to the daemon without error).
            ws.sendText("{\"t\":\"k\",\"c\":\"KeyR\",\"d\":true}", true).join();
            ws.sendText("{\"t\":\"k\",\"c\":\"KeyR\",\"d\":false}", true).join();
            Thread.sleep(500);
            assertThat(client.closeCode.get())
                .as("input did not tear the console down").isEqualTo(-1);

            // Clause 2: revoke the grant; the tenant's OPEN console is closed 1008.
            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE);
            // The PRODUCTION endpoint revalidates every TERMINAL_REVALIDATION_INTERVAL_MS
            // (15s); allow two intervals so a tick that just passed still gets its next one.
            assertThat(client.closed.await(35, TimeUnit.SECONDS))
                .as("a revoked tenant's OPEN console is disconnected").isTrue();
            assertThat(client.closeCode.get())
                .as("the disconnect is a 1008 policy close").isEqualTo(1008);
        } finally {
            if (ws != null) {
                ws.abort();
            }
            try {
                service.destroy(instanceId);
            } catch (RuntimeException ignored) {
                // best effort; forceDelete below is the daemon-truth cleanup
            }
            remote.forceDelete(handle);
            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE);
            Models.get(InstanceModel.class).delete(instanceId);
            AuthModels.users().delete(userId);
            try {
                System.out.println("=== cleanup: shared objects -> "
                    + remote.releaseControllerSharedObjects());
                System.out.println("=== cleanup: authorized_keys -> "
                    + remote.releaseAuthorizedKeys());
                remote.removeTrustEntry(enrolledFingerprint);
            } catch (IOException ignored) {
                // nothing enrolled, nothing to remove
            }
        }
    }

    // -----------------------------------------------------------------------

    private static int user(String label) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, label + "@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "FB " + label);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static int vmRecord(String name, int hostId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:incus_vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", VM_IMAGE, "memory_limit_mb", 512)));
        row.set(InstanceModel.SERVER_ID, hostId);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    /** Records the first binary frame and the close code. */
    private static final class RecordingClient implements WebSocket.Listener {

        final CountDownLatch binaryArrived = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<byte[]> lastBinary = new AtomicReference<>();
        final AtomicInteger closeCode = new AtomicInteger(-1);

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            this.lastBinary.set(bytes);
            this.binaryArrived.countDown();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            this.closeCode.set(statusCode);
            this.closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.closed.countDown();
        }
    }
}
