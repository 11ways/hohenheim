package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.VmFramebufferHandler;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.model.GrantSubjectType;
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
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        try {
            FakeSession session = new FakeSession(new UserPrincipal(userId, "Granted"), instanceId);
            new VmFramebufferHandler(session, instanceId).onOpen();

            // A container has no framebuffer, so the framebuffer operation does not apply: the tab
            // hides, and the socket refuses 1008 exactly like a missing grant, naming nothing.
            assertThat(session.closeCode).isEqualTo(1008);
            assertThat(session.texts).as("no kind oracle in the refusal").isEmpty();
        } finally {
            cleanup(userId, instanceId);
        }
    }

    /**
     * A product-generated VM's screen is its product's: the framebuffer operation does not apply to it, so a viewer
     * holding the grant is refused 1008 where the same viewer on an authored VM is admitted.
     */
    @Test
    void refusesAGeneratedVmWith1008EvenWhenGranted() throws Exception {
        int userId = user("fb-generated");
        int[] id = new int[1];
        OwnedInstances.inScopeUnchecked("site", SiteModel.MODEL_ID, 424242,
            () -> id[0] = vmInstance("fb-generated-vm", InstanceModel.STATUS_STOPPED));
        int instanceId = id[0];
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        try {
            FakeSession session = new FakeSession(new UserPrincipal(userId, "Granted"), instanceId);
            VmFramebufferHandler handler = new VmFramebufferHandler(session, instanceId);
            handler.onOpen();

            // An authored VM in this state closes 1000 "not running" (the test below): admitted. This one never is.
            assertThat(session.closeCode).as("a generated VM's framebuffer is refused by policy").isEqualTo(1008);
            assertThat(session.texts).as("and the refusal names nothing").isEmpty();
            assertThat(handler.revalidate()).as("nor does it ever revalidate").isFalse();
        } finally {
            // A generated record is its product's to delete: the cleanup runs in that product's scope.
            OwnedInstances.inScopeUnchecked("site", SiteModel.MODEL_ID, 424242, () -> cleanup(userId, instanceId));
        }
    }

    @Test
    void closesGracefullyWhenTheVmIsNotRunning() throws Exception {
        int userId = user("fb-stopped");
        int instanceId = vmInstance("fb-stopped-vm", InstanceModel.STATUS_STOPPED);
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
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

            RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE, true);
            assertThat(handler.revalidate())
                .as("a live MANAGE grant keeps the console open")
                .isTrue();

            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
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
        return ApiSupport.user(label + "@hohenheim.local", "FB " + label);
    }

    private static int vmInstance(String name, String status) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:vm");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine/3.22/cloud")));
        row.set(InstanceModel.STATUS, status);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static void cleanup(int userId, int instanceId) {
        RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE);
        HardDeletes.byId(Models.get(InstanceModel.class), instanceId);
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
