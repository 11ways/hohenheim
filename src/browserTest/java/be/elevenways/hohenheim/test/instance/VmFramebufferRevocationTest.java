package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.FramebufferSource;
import be.elevenways.hohenheim.server.instance.VmFramebufferHandler;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
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
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.WebSocketEndpoint;
import be.elevenways.zenit.common.session.Session;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes both framebuffer-console gate clauses over a REAL socket, in CI, without a live
 * daemon: a granted non-admin viewer connects, framebuffer bytes flow (a binary frame
 * arrives) and input reaches the source (a keystroke is recorded), then the MANAGE grant
 * is REVOKED and core's default-on revalidator CLOSES the open socket with 1008 -- the
 * viewer is disconnected, not merely refused on the next connect. The daemon-facing half
 * (real SPICE input, real VGA snapshots against daystrom) is VmFramebufferConsoleLiveTest;
 * the fake source here isolates the authorization mechanism.
 */
class VmFramebufferRevocationTest extends HohenheimTestBase {

    private static final long TEST_REVALIDATION_MS = 100;

    private static final AtomicReference<Integer> WS_INSTANCE_ID = new AtomicReference<>();
    private static final RecordingSource WS_SOURCE = new RecordingSource();

    @SuppressWarnings("unused")
    private static final WebSocketEndpoint TEST_FRAMEBUFFER = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheimtest", "vm_framebuffer_reval"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("test-framebuffer-reval").build())
        .requiresLogin()
        .revalidateEvery(TEST_REVALIDATION_MS)
        .handler(session -> new VmFramebufferHandler(session, WS_INSTANCE_ID.get(),
            (instanceId, serverName, handle) -> WS_SOURCE.reset()))
        .build();

    @Test
    void framebufferFlowsThenRevocationClosesTheSocketWith1008() throws Exception {
        int userId = user("fb-reval-socket");
        int instanceId = runningVm("fb-reval-socket-vm");
        WS_INSTANCE_ID.set(instanceId);
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        String token = sessionFor(userId).token();

        RecordingClient client = new RecordingClient();
        WebSocket ws = connect(token, client);
        try {
            // Clause 1: framebuffer bytes flow -- a binary snapshot frame arrives.
            assertThat(client.binaryArrived.await(5, TimeUnit.SECONDS))
                .as("a granted viewer receives framebuffer snapshot frames")
                .isTrue();
            assertThat(client.lastBinary.get()).isNotNull().isNotEmpty();

            // Clause 1: input reaches the source (a keystroke lands on the console).
            ws.sendText("{\"t\":\"k\",\"c\":\"KeyA\",\"d\":true}", true).join();
            awaitTrue(() -> WS_SOURCE.keys.contains("KeyA:true"));
            assertThat(WS_SOURCE.keys).contains("KeyA:true");

            // Clause 2: revoke the grant; the OPEN socket must be closed 1008.
            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE);
            assertThat(client.closed.await(5, TimeUnit.SECONDS))
                .as("a revoked viewer's OPEN console is disconnected, not merely refused later")
                .isTrue();
            assertThat(client.closeCode.get())
                .as("the close code is 1008 (policy violation), the revocation signal")
                .isEqualTo(1008);
        } finally {
            ws.abort();
            cleanup(userId, instanceId);
        }
    }

    /**
     * The REAL floor of the framebuffer console, which the test above cannot see.
     *
     * That one grants and revokes MANAGE, and MANAGE implies CONSOLE, so it passes
     * whichever of the two verbs the handler asks for -- it pinned nothing after the
     * commit that split the instance capability into enforced verbs, and four docblocks
     * went on claiming MANAGE while the code checked CONSOLE. A CONSOLE-ONLY grantee is
     * what distinguishes the two: they reach the framebuffer (intended -- a rescue console
     * is exactly what CONSOLE is for), and a holder of the wider-audience VIEW verb does
     * not.
     */
    @Test
    void consoleAloneOpensTheFramebufferAndViewAloneDoesNot() throws Exception {
        int consoleUserId = user("fb-console-only");
        int viewUserId = user("fb-view-only");
        int instanceId = runningVm("fb-console-only-vm");
        WS_INSTANCE_ID.set(instanceId);
        RecordGrants.grant(GrantSubjectType.USER, consoleUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONSOLE, true);
        RecordGrants.grant(GrantSubjectType.USER, viewUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.VIEW, true);

        RecordingClient consoleClient = new RecordingClient();
        WebSocket consoleSocket = connect(sessionFor(consoleUserId).token(), consoleClient);
        try {
            // 1. THE FLOOR: console and nothing else. No manage grant exists for this
            //    user, so a handler still asking for manage would close this socket.
            assertThat(consoleClient.binaryArrived.await(5, TimeUnit.SECONDS))
                .as("step 1: a CONSOLE-only grantee receives framebuffer frames")
                .isTrue();
            assertThat(consoleClient.lastBinary.get()).isNotNull().isNotEmpty();

            // 2. Input too -- the framebuffer is a two-way rescue console, and a read-only
            //    half would be a different (and useless) thing to have proven.
            consoleSocket.sendText("{\"t\":\"k\",\"c\":\"KeyB\",\"d\":true}", true).join();
            awaitTrue(() -> WS_SOURCE.keys.contains("KeyB:true"));
            assertThat(WS_SOURCE.keys).contains("KeyB:true");

            // 3. THE OTHER SIDE, so step 1 is a floor and not "any grant will do": VIEW is
            //    the widest instance verb (every other one implies it) and it does NOT
            //    reach the guest's screen and keyboard.
            RecordingClient viewClient = new RecordingClient();
            WebSocket viewSocket = connect(sessionFor(viewUserId).token(), viewClient);
            try {
                assertThat(viewClient.closed.await(5, TimeUnit.SECONDS))
                    .as("step 3: a VIEW-only grantee is closed off the framebuffer")
                    .isTrue();
                assertThat(viewClient.closeCode.get())
                    .as("step 3: with the policy-violation code, the refusal signal")
                    .isEqualTo(1008);
                assertThat(viewClient.lastBinary.get())
                    .as("step 3: and never received a single frame of the guest's screen")
                    .isNull();
            } finally {
                viewSocket.abort();
            }
        } finally {
            consoleSocket.abort();
            RecordGrants.revoke(GrantSubjectType.USER, consoleUserId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.CONSOLE);
            RecordGrants.revoke(GrantSubjectType.USER, viewUserId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.VIEW);
            Models.get(InstanceModel.class).delete(instanceId);
            AuthModels.users().delete(consoleUserId);
            AuthModels.users().delete(viewUserId);
        }
    }

    // -----------------------------------------------------------------------

    private WebSocket connect(String sessionToken, RecordingClient client) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .buildAsync(URI.create("ws://localhost:" + getServerPort() + "/test-framebuffer-reval"),
                client)
            .join();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier probe) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!probe.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

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

    private static int runningVm(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine/3.22/cloud")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static void cleanup(int userId, int instanceId) {
        RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE);
        Models.get(InstanceModel.class).delete(instanceId);
        AuthModels.users().delete(userId);
    }

    /** A fake source: an ever-changing snapshot (so a frame always sends) + recorded input. */
    private static final class RecordingSource implements FramebufferSource {

        final List<String> keys = new CopyOnWriteArrayList<>();
        private int tick;

        RecordingSource reset() {
            this.keys.clear();
            this.tick = 0;
            return this;
        }

        @Override
        public byte @NonNull [] snapshot() {
            // A distinct byte each call so the handler's change-detector always emits.
            return new byte[] {(byte) (this.tick++), 0x50, 0x4E, 0x47};
        }

        @Override public void key(@NonNull String code, boolean down) { this.keys.add(code + ":" + down); }
        @Override public void mousePosition(int x, int y, int buttonsMask) {}
        @Override public void mouseButton(int button, boolean down, int buttonsMask) {}
        @Override public void close() {}
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
