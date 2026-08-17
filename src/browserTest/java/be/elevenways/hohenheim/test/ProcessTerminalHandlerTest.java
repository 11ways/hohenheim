package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.process.IpcChannel;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ProcessTerminalHandler;
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
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.routing.WebSocketEndpoint;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import be.elevenways.zenit.server.http.UndertowWebSocketSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessTerminalHandlerTest extends HohenheimTestBase {

    // Production uses HohenheimEndpoints.TERMINAL_REVALIDATION_INTERVAL_MS (15s);
    // the test endpoint below rides the same mechanism with a short interval so
    // revocation is observed within the test's patience.
    private static final long TEST_REVALIDATION_MS = 100;

    private static final AtomicReference<Integer> WS_SITE_ID = new AtomicReference<>();
    private static final AtomicReference<ManagedProcess> WS_PROCESS = new AtomicReference<>();
    private static final AtomicReference<IpcChannel> WS_IPC = new AtomicReference<>();
    private static final AtomicReference<WebSocketSession> WS_SESSION = new AtomicReference<>();

    @SuppressWarnings("unused")
    private static final WebSocketEndpoint TEST_TERMINAL = WebSocketEndpoint.builder()
        .identifier(Identifier.of("hohenheimtest", "terminal_reval"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("test-terminal-reval").build())
        .requiresLogin()
        .revalidateEvery(TEST_REVALIDATION_MS)
        .handler(session -> {
            WS_SESSION.set(session);
            return new ProcessTerminalHandler(
                session, WS_SITE_ID.get(), WS_PROCESS.get(), WS_IPC.get());
        })
        .build();

    @Test
    void refusedTerminalIsInactiveBeforeCloseAndRejectsLaterMessages() throws Exception {
        Path marker = Files.createTempFile("terminal-refusal", ".txt");
        Files.delete(marker);
        Process child = new ProcessBuilder("/bin/sh", "-c",
            "IFS= read -r line; printf '%s' \"$line\" > \"$1\"", "terminal-child",
            marker.toString()).start();
        RefusedSession session = new RefusedSession();
        ProcessTerminalHandler handler = new ProcessTerminalHandler(session, 123,
            new ManagedProcess(child, 0, null, 123, null), null);

        try {
            handler.onOpen();
            handler.onTextMessage("must-not-reach-stdin\n");
            Thread.sleep(250);

            assertThat(session.closeCode).isEqualTo(1008);
            assertThat(Files.exists(marker)).isFalse();
        } finally {
            child.destroyForcibly();
            child.waitFor();
            Files.deleteIfExists(marker);
        }
    }

    @Test
    void passiveViewerIsClosed1008WithinOneRevalidationInterval() throws Exception {
        TerminalFixture fixture = new TerminalFixture("terminal-passive");
        try {
            WS_IPC.set(null);
            RecordingClient client = new RecordingClient();
            WebSocket ws = connectTerminal(fixture.sessionToken, client);
            try {
                fixture.revokeGrant();

                // The viewer sends NOTHING: the periodic revalidation alone
                // must cut the session off with 1008 (policy violation).
                assertThat(client.closed.await(5, TimeUnit.SECONDS))
                    .as("a passive terminal viewer must be closed once the grant is revoked")
                    .isTrue();
                assertThat(client.closeCode.get()).isEqualTo(1008);
            } finally {
                ws.abort();
            }
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void revokedGrantClosesActiveTerminalsAndForwardsNeitherStdinNorResize() throws Exception {
        TerminalFixture fixture = new TerminalFixture("terminal-revocation");
        try (RecordingIpcChannel ipc = new RecordingIpcChannel()) {
            WS_IPC.set(ipc);
            RecordingClient client = new RecordingClient();
            WebSocket ws = connectTerminal(fixture.sessionToken, client);
            try {
                // Prove the pipe works while the grant is live, so the
                // post-revocation negatives below are meaningful.
                ws.sendText("before\n", true).join();
                awaitFileContains(fixture.outFile, "before");
                ws.sendText("{\"type\":\"resize\",\"cols\":120,\"rows\":40}", true).join();
                awaitIpcMessages(ipc, 2);   // janeway_propose_geometry + janeway_redraw

                fixture.revokeGrant();

                // Wait for the revalidator tick to flip the session's cached
                // authorization flag: from that moment core refuses every
                // inbound frame BEFORE it reaches the handler, so nothing sent
                // now can touch stdin or the IPC channel.
                UndertowWebSocketSession live = (UndertowWebSocketSession) WS_SESSION.get();
                awaitInvalidated(live);
                trySend(ws, "must-not-reach-stdin\n");
                trySend(ws, "{\"type\":\"resize\",\"cols\":80,\"rows\":24}");

                assertThat(client.closed.await(5, TimeUnit.SECONDS))
                    .as("an active terminal must be closed once the grant is revoked")
                    .isTrue();
                assertThat(client.closeCode.get()).isEqualTo(1008);

                // The detached log listener no longer forwards process output.
                fixture.managed.appendLog("must-not-reach-websocket");
                Thread.sleep(250);

                assertThat(Files.readString(fixture.outFile)).isEqualTo("before\n");
                assertThat(ipc.messages).hasSize(2);
                assertThat(String.join("", client.messages))
                    .doesNotContain("must-not-reach-websocket");
            } finally {
                ws.abort();
            }
        } finally {
            fixture.cleanup();
        }
    }

    // -----------------------------------------------------------------------

    /** A granted user + site + running child wired into the test endpoint's statics. */
    private static final class TerminalFixture {

        final int userId;
        final int siteId;
        final Process child;
        final ManagedProcess managed;
        final Path outFile;
        final String sessionToken;

        TerminalFixture(String label) throws Exception {
            Row user = AuthModels.users().createEmptyRow();
            user.set(UserModel.EMAIL, label + "@hohenheim.local");
            user.set(UserModel.DISPLAY_NAME, "Terminal " + label);
            user.set(UserModel.ENABLED, true);
            user.set(UserModel.CREATED_AT, Instant.now());
            user.set(UserModel.UPDATED_AT, Instant.now());
            AuthModels.users().save(user);
            this.userId = user.get(UserModel.ID);

            var siteModel = Models.get(SiteModel.class);
            Row site = siteModel.createEmptyRow();
            site.set(SiteModel.NAME, "Terminal " + label + " Site");
            site.set(SiteModel.SLUG, label + "-site");
            site.set(SiteModel.SITE_TYPE, "hohenheim:static");
            site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
            site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
            site.set(SiteModel.ENABLED, true);
            siteModel.save(site);
            this.siteId = site.get(SiteModel.ID);

            RecordGrants.grant(GrantSubjectType.USER, this.userId, SiteModel.MODEL_ID, this.siteId,
                HohenheimAccess.MANAGE, true);

            this.outFile = Files.createTempFile(label, ".txt");
            Files.delete(this.outFile);
            this.child = new ProcessBuilder("/bin/sh", "-c",
                "while IFS= read -r line; do printf '%s\\n' \"$line\" >> \"$1\"; done",
                "terminal-child", this.outFile.toString()).start();
            this.managed = new ManagedProcess(this.child, 0, null, this.siteId, null);

            Session session = Zenit.getSessionStore().create();
            session.set(AuthKeys.USER_ID, (long) this.userId);
            Zenit.getSessionStore().save(session);
            this.sessionToken = session.token().secret();

            WS_SITE_ID.set(this.siteId);
            WS_PROCESS.set(this.managed);
            WS_SESSION.set(null);
        }

        void revokeGrant() {
            RecordGrants.revoke(GrantSubjectType.USER, this.userId, SiteModel.MODEL_ID, this.siteId,
                HohenheimAccess.MANAGE);
        }

        void cleanup() throws Exception {
            this.revokeGrant();
            this.child.destroyForcibly();
            this.child.waitFor();
            Files.deleteIfExists(this.outFile);
            Models.get(SiteModel.class).delete(this.siteId);
            AuthModels.users().delete(this.userId);
        }
    }

    private WebSocket connectTerminal(String sessionToken, RecordingClient client) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .buildAsync(URI.create("ws://localhost:" + getServerPort() + "/test-terminal-reval"),
                client)
            .join();
    }

    private static void trySend(WebSocket ws, String message) {
        try {
            ws.sendText(message, true).join();
        } catch (Exception ignored) {
            // The server may already have completed the 1008 close.
        }
    }

    private static void awaitFileContains(Path file, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file) && Files.readString(file).contains(expected)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for " + file + " to contain " + expected);
    }

    private static void awaitIpcMessages(RecordingIpcChannel ipc, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (ipc.messages.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(ipc.messages).hasSize(count);
    }

    private static void awaitInvalidated(UndertowWebSocketSession session) throws Exception {
        assertThat(session).isNotNull();
        long deadline = System.currentTimeMillis() + 5000;
        while (session.isAuthorizationValid() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(session.isAuthorizationValid())
            .as("the revalidator must flip the cached authorization flag within the interval")
            .isFalse();
    }

    /** Client that records incoming text frames and the close code it receives. */
    private static final class RecordingClient implements WebSocket.Listener {

        final List<String> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger closeCode = new AtomicInteger(-1);
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.set(statusCode);
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.countDown();
        }
    }

    private static final class RefusedSession implements WebSocketSession {
        private int closeCode = -1;

        @Override
        public <T> T getParameter(ParameterDefinition<T> parameter) {
            return null;
        }

        @Override public void sendText(String message) {}
        @Override public void sendBinary(byte[] data) {}
        @Override public void close() { this.closeCode = 1000; }
        @Override public void close(int code, String reason) { this.closeCode = code; }
        @Override public boolean isOpen() { return this.closeCode < 0; }
    }

    private static final class RecordingIpcChannel extends IpcChannel {
        private final List<Map<String, Object>> messages = new CopyOnWriteArrayList<>();

        private RecordingIpcChannel() throws IOException {}

        @Override public void send(Map<String, Object> message) { messages.add(message); }
    }
}
