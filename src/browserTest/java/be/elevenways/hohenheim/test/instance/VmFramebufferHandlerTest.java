package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.VmFramebufferHandler;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The VM framebuffer console's authorization contract, proven WITHOUT a live daemon: the
 * onOpen gates (missing grant, wrong kind, not running) and the revalidate() check that
 * core's default-on revalidator turns into a 1008 close. The full open-socket-then-revoke
 * -to-1008 journey against a real VM is {@code VmFramebufferConsoleLiveTest}; this test
 * pins the same concrete handler's decisions so a refactor cannot silently loosen them.
 */
class VmFramebufferHandlerTest extends HohenheimTestBase {

    @Test
    void refusesAViewerWithoutTheManageGrantWith1008() throws Exception {
        int userId = user("fb-nogrant");
        int instanceId = vmInstance("fb-nogrant-vm", InstanceModel.STATUS_RUNNING);
        try {
            // No grant: the per-record MANAGE check fails, so onOpen must close 1008
            // BEFORE it ever tries to reach the daemon.
            FakeSession session = new FakeSession(new UserPrincipal(userId, "No Grant"), instanceId);
            VmFramebufferHandler handler = new VmFramebufferHandler(session, instanceId);
            handler.onOpen();

            assertThat(session.closeCode)
                .as("an ungranted viewer is refused 1008, not merely served nothing")
                .isEqualTo(1008);
            assertThat(session.texts).noneMatch(t -> t.contains("framebuffer"));
        } finally {
            cleanup(userId, instanceId);
        }
    }

    @Test
    void refusesAContainerInstanceWith1008EvenWhenGranted() throws Exception {
        int userId = user("fb-container");
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, "fb-container-inst");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        instances.save(row);
        int instanceId = row.get(InstanceModel.ID);
        RecordGrants.grant("user", userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        try {
            FakeSession session = new FakeSession(new UserPrincipal(userId, "Granted"), instanceId);
            new VmFramebufferHandler(session, instanceId).onOpen();

            // A container has no framebuffer; the tab hides, and the socket refuses 1008.
            assertThat(session.closeCode).isEqualTo(1008);
            assertThat(String.join("", session.texts)).contains("not a VM");
        } finally {
            cleanup(userId, instanceId);
        }
    }

    @Test
    void closesGracefullyWhenTheVmIsNotRunning() throws Exception {
        int userId = user("fb-stopped");
        int instanceId = vmInstance("fb-stopped-vm", InstanceModel.STATUS_STOPPED);
        RecordGrants.grant("user", userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        try {
            FakeSession session = new FakeSession(new UserPrincipal(userId, "Granted"), instanceId);
            new VmFramebufferHandler(session, instanceId).onOpen();

            // A stopped VM has no framebuffer to attach; this is a normal close, not a
            // policy violation, and it never reached the daemon.
            assertThat(session.closeCode).isEqualTo(1000);
            assertThat(String.join("", session.texts)).contains("not running");
        } finally {
            cleanup(userId, instanceId);
        }
    }

    @Test
    void revalidateFollowsTheGrantSoRevocationClosesTheSocket() throws Exception {
        int userId = user("fb-reval");
        int instanceId = vmInstance("fb-reval-vm", InstanceModel.STATUS_RUNNING);
        try {
            Principal principal = new UserPrincipal(userId, "Reval");
            FakeSession session = new FakeSession(principal, instanceId);
            VmFramebufferHandler handler = new VmFramebufferHandler(session, instanceId);

            RecordGrants.grant("user", userId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE, true);
            assertThat(handler.revalidate())
                .as("a live MANAGE grant keeps the console open")
                .isTrue();

            RecordGrants.revoke("user", userId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE);
            assertThat(handler.revalidate())
                .as("once the grant is revoked, revalidate() returns false and core closes 1008")
                .isFalse();
        } finally {
            cleanup(userId, instanceId);
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

    private static int vmInstance(String name, String status) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:incus_vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine/3.22/cloud")));
        row.set(InstanceModel.STATUS, status);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static void cleanup(int userId, int instanceId) {
        RecordGrants.revoke("user", userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE);
        Models.get(InstanceModel.class).delete(instanceId);
        AuthModels.users().delete(userId);
    }

    /** A minimal in-process session that records what the handler did to it. */
    private static final class FakeSession implements WebSocketSession {

        private final Principal principal;
        private final int instanceId;
        final List<String> texts = new CopyOnWriteArrayList<>();
        int closeCode = -1;

        FakeSession(Principal principal, int instanceId) {
            this.principal = principal;
            this.instanceId = instanceId;
        }

        @Override
        public <T> @Nullable T getParameter(ParameterDefinition<T> parameter) {
            return null;
        }

        @Override public @Nullable Principal getPrincipal() { return this.principal; }
        @Override public void sendText(String message) { this.texts.add(message); }
        @Override public void sendBinary(byte[] data) {}
        @Override public void close() { if (this.closeCode < 0) this.closeCode = 1000; }
        @Override public void close(int code, String reason) {
            if (this.closeCode < 0) this.closeCode = code;
        }
        @Override public boolean isOpen() { return this.closeCode < 0; }
    }
}
