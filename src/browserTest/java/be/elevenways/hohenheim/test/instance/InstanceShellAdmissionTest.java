package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceShellHandler;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.hohenheim.test.HardDeletes;
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
 * The shell socket admits exactly whom the open-shell operation is offered to, proven without a daemon: a generated
 * instance and an ungranted viewer are refused by policy before anything is resolved, and a granted viewer on an
 * authored instance is admitted through to the shell's own funnel.
 *
 * @author Jelle De Loecker
 * @since 0.9.0
 */
class InstanceShellAdmissionTest extends HohenheimTestBase {

    @Test
    void theShellSocketAdmitsOnlyWhereTheShellOperationIsOffered() {
        int userId = ApiSupport.user("shell-admission@hohenheim.local", "Shell Admission");
        Principal viewer = new UserPrincipal(userId, "Shell Admission");
        int authored = instance("shell-authored");
        int[] id = new int[1];
        OwnedInstances.inScopeUnchecked("site", SiteModel.MODEL_ID, 424242, () -> id[0] = instance("shell-generated"));
        int generated = id[0];
        try {
            // 1. Without the shell grant the socket refuses by policy and names nothing.
            FakeSession ungranted = open(viewer, authored);
            assertThat(ungranted.closeReason).as("step 1: an ungranted viewer is refused by policy")
                .isEqualTo("forbidden");
            assertThat(ungranted.texts).as("step 1: and the refusal names nothing").isEmpty();

            // 2. The same grant on a product-generated instance still never opens its shell.
            RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, generated, HohenheimAccess.SHELL,
                true);
            FakeSession onGenerated = open(viewer, generated);
            assertThat(onGenerated.closeReason).as("step 2: a generated instance's shell is refused by policy")
                .isEqualTo("forbidden");
            assertThat(onGenerated.texts).as("step 2: naming nothing").isEmpty();
            assertThat(new InstanceShellHandler(onGenerated, generated).revalidate())
                .as("step 2: and it never revalidates").isFalse();

            // 3. Granted on an authored instance, the viewer is admitted: the shell's own funnel answers (this stopped
            //    workload has no shell to open), by name, in the terminal.
            RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, authored, HohenheimAccess.SHELL,
                true);
            FakeSession admitted = open(viewer, authored);
            assertThat(admitted.closeReason).as("step 3: admitted past the socket, to the shell's funnel")
                .isEqualTo("refused");
            assertThat(String.join("", admitted.texts)).as("step 3: which names its own refusal").isNotBlank();
            assertThat(new InstanceShellHandler(admitted, authored).revalidate())
                .as("step 3: and the admission revalidates").isTrue();
        } finally {
            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, authored, HohenheimAccess.SHELL);
            RecordGrants.revoke(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, generated,
                HohenheimAccess.SHELL);
            HardDeletes.byId(Models.get(InstanceModel.class), authored);
            // A generated record is its product's to delete: the cleanup runs in that product's scope.
            OwnedInstances.inScopeUnchecked("site", SiteModel.MODEL_ID, 424242,
                () -> HardDeletes.byId(Models.get(InstanceModel.class), generated));
            AuthModels.users().delete(userId);
        }
    }

    private static FakeSession open(Principal viewer, int instanceId) {
        FakeSession session = new FakeSession(viewer);
        new InstanceShellHandler(session, instanceId).onOpen();
        return session;
    }

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    /** A minimal in-process session that records what the handler did to it. */
    private static final class FakeSession implements WebSocketSession {

        private final Principal principal;
        final List<String> texts = new CopyOnWriteArrayList<>();
        @Nullable String closeReason;

        FakeSession(Principal principal) {
            this.principal = principal;
        }

        @Override
        public <T> @Nullable T getParameter(ParameterDefinition<T> parameter) {
            return null;
        }

        @Override public @Nullable Principal getPrincipal() { return this.principal; }
        @Override public void sendText(String message) { this.texts.add(message); }
        @Override public void sendBinary(byte[] data) {}
        @Override public void close() { if (this.closeReason == null) this.closeReason = "normal"; }
        @Override public void close(int code, String reason) {
            if (this.closeReason == null) this.closeReason = reason;
        }
        @Override public boolean isOpen() { return this.closeReason == null; }
    }
}
