package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.process.IpcChannel;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ProcessTerminalHandler;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessTerminalHandlerTest extends HohenheimTestBase {

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
    void revokedGrantClosesActiveTerminalsAndForwardsNeitherStdinNorResize() throws Exception {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "terminal-revocation@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Terminal Revocation");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        int userId = user.get(UserModel.ID);

        SiteModel siteModel = Models.get(SiteModel.class);
        Row site = siteModel.createEmptyRow();
        site.set(SiteModel.NAME, "Terminal Revocation Site");
        site.set(SiteModel.SLUG, "terminal-revocation-site");
        site.set(SiteModel.SITE_TYPE, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        siteModel.save(site);
        int siteId = site.get(SiteModel.ID);

        RecordGrants.grant("user", userId, SiteModel.MODEL_ID, siteId,
            HohenheimAccess.MANAGE, true);
        UserPrincipal principal = new UserPrincipal(userId, "Terminal Revocation");
        Path marker = Files.createTempFile("terminal-revocation", ".txt");
        Files.delete(marker);
        Process child = new ProcessBuilder("/bin/sh", "-c",
            "IFS= read -r line; printf '%s' \"$line\" > \"$1\"", "terminal-child",
            marker.toString()).start();
        ManagedProcess managed = new ManagedProcess(child, 0, null, siteId, null);

        GrantedSession stdinSession = new GrantedSession(principal);
        GrantedSession resizeSession = new GrantedSession(principal);
        try (RecordingIpcChannel ipc = new RecordingIpcChannel()) {
            ProcessTerminalHandler stdinHandler = new ProcessTerminalHandler(
                stdinSession, siteId, managed, null);
            ProcessTerminalHandler resizeHandler = new ProcessTerminalHandler(
                resizeSession, siteId, managed, ipc);
            stdinHandler.onOpen();
            resizeHandler.onOpen();

            RecordGrants.revoke("user", userId, SiteModel.MODEL_ID, siteId,
                HohenheimAccess.MANAGE);
            stdinHandler.onTextMessage("must-not-reach-stdin\n");
            resizeHandler.onTextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":40}");
            managed.appendLog("must-not-reach-websocket");
            Thread.sleep(250);

            assertThat(stdinSession.closeCode).isEqualTo(1008);
            assertThat(resizeSession.closeCode).isEqualTo(1008);
            assertThat(Files.exists(marker)).isFalse();
            assertThat(ipc.messages).isEmpty();
            assertThat(stdinSession.sent).doesNotContain("must-not-reach-websocket");
            assertThat(resizeSession.sent).doesNotContain("must-not-reach-websocket");
        } finally {
            RecordGrants.revoke("user", userId, SiteModel.MODEL_ID, siteId,
                HohenheimAccess.MANAGE);
            child.destroyForcibly();
            child.waitFor();
            Files.deleteIfExists(marker);
            siteModel.delete(siteId);
            AuthModels.users().delete(userId);
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

    private static final class GrantedSession implements WebSocketSession {
        private final Principal principal;
        private final List<String> sent = new ArrayList<>();
        private int closeCode = -1;

        private GrantedSession(Principal principal) {
            this.principal = principal;
        }

        @Override public <T> T getParameter(ParameterDefinition<T> parameter) { return null; }
        @Override public Principal getPrincipal() { return principal; }
        @Override public void sendText(String message) { sent.add(message); }
        @Override public void sendBinary(byte[] data) {}
        @Override public void close() { closeCode = 1000; }
        @Override public void close(int code, String reason) { closeCode = code; }
        @Override public boolean isOpen() { return closeCode < 0; }
    }

    private static final class RecordingIpcChannel extends IpcChannel {
        private final List<Map<String, Object>> messages = new ArrayList<>();

        private RecordingIpcChannel() throws IOException {}

        @Override public void send(Map<String, Object> message) { messages.add(message); }
    }
}
