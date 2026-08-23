package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceShell;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The interactive shell's THREE gates, each proved as a refusal AND as an absence in the
 * UI, with a positive anchor beside every negative so none of them is vacuous.
 *
 * <p>No daemon is needed: every refusal here fires before any driver call, which is the
 * point -- a gate that only holds once the daemon answers is not a gate. The half that
 * genuinely needs a container (the session itself, the uid it lands as, resize, teardown)
 * is {@code InstanceShellLiveTest}.</p>
 *
 * AIDEV-NOTE: the capability walk under test is the PRINCIPAL-only one, because that is
 * what a WebSocket handshake has. Driving it through
 * {@code HohenheimAccess.requireOperationCapability} instead would have passed for
 * everyone -- that funnel default-ALLOWS when no tenant conduit is in flight, which is
 * exactly a socket. This suite would not have caught that; the direct principal argument
 * on {@code InstanceShell.open} is what makes it catchable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceShellGateTest extends HohenheimTestBase {

    private static final String PREFIX = "shellgate-";

    /** A docker_container: a real Docker workload that runs as ROOT in its container. */
    private static int rootWorkloadId;

    /** A workspace on an Incus host: a non-root uid, on a runtime with no shell lane. */
    private static int incusWorkspaceId;

    private static int shellUserId;
    private static int consoleUserId;
    private static int strangerUserId;
    private static Principal shellPrincipal;
    private static Principal strangerPrincipal;
    private static String shellSession;
    private static String consoleSession;

    @BeforeAll
    static void seed() {
        rootWorkloadId = dockerInstance(PREFIX + "root-workload");
        incusWorkspaceId = incusWorkspace(PREFIX + "incus-workspace");

        shellUserId = tenant("shell@shellgate.test", "Shell Delegate");
        consoleUserId = tenant("console@shellgate.test", "Console Delegate");
        strangerUserId = tenant("stranger@shellgate.test", "Stranger");
        shellPrincipal = new UserPrincipal(shellUserId, "Shell Delegate");
        strangerPrincipal = new UserPrincipal(strangerUserId, "Stranger");

        // The delegation under test: `shell` on both records, and nothing else.
        for (int instanceId : List.of(rootWorkloadId, incusWorkspaceId)) {
            RecordGrants.grant(GrantSubjectType.USER, shellUserId, InstanceModel.MODEL_ID,
                instanceId, HohenheimAccess.SHELL, true);
        }
        // The counterfactual delegate: console is the OTHER terminal verb, and it must not
        // reach this one.
        RecordGrants.grant(GrantSubjectType.USER, consoleUserId, InstanceModel.MODEL_ID,
            rootWorkloadId, HohenheimAccess.CONSOLE, true);

        shellSession = sessionOf(shellUserId);
        consoleSession = sessionOf(consoleUserId);
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX))
                .withTrashed().all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
    }

    /**
     * GATE 1, the capability: the tab is offered and reachable for a {@code shell}
     * delegate and for nobody else -- console, the neighbouring terminal verb, included.
     */
    @Test
    @Order(1)
    void theShellTabAnswersToTheShellCapabilityAndNeverToConsole() throws Exception {
        String record = "/manage/instances/" + rootWorkloadId;

        // 1. The console delegate reaches the record at all -- without this the rest is
        //    vacuous, because a 403 would hide the tab for the wrong reason.
        HttpResponse<String> asConsole = httpGet(record, consoleSession);
        assertThat(asConsole.statusCode())
            .as("step 1: the console delegate sees the record")
            .isEqualTo(200);

        // 2. ...and is offered no shell tab, and cannot reach the route by hand. A hidden
        //    tab is an affordance; the 404 is the gate.
        assertThat(asConsole.body())
            .withFailMessage("step 2: a console-only delegate is offered the SHELL tab --"
                + " console is stdin to the workload's own process, never a new program")
            .doesNotContain("/page/shell");
        assertThat(httpGet(record + "/page/shell", consoleSession).statusCode())
            .withFailMessage("step 2: a console-only delegate can reach the shell route by hand")
            .isEqualTo(404);

        // 3. Positive anchor: the shell delegate is offered it AND can open it, so step 2
        //    is a gate rather than a tab that exists for nobody.
        HttpResponse<String> asShell = httpGet(record, shellSession);
        assertThat(asShell.body())
            .as("step 3: a shell delegate is offered the tab")
            .contains("/page/shell");
        assertThat(httpGet(record + "/page/shell", shellSession).statusCode())
            .as("step 3: and can open it")
            .isEqualTo(200);
    }

    /**
     * GATE 2, the uid: a workload running as root in its container is refused BY NAME, and
     * the tab says so instead of offering a terminal that would never open.
     */
    @Test
    @Order(2)
    void aRootRunningWorkloadIsRefusedByNameAndSaysSoOnItsTab() throws Exception {
        // 1. The tab renders for the capability holder -- the refusal is content, not a 404.
        HttpResponse<String> page = httpGet(
            "/manage/instances/" + rootWorkloadId + "/page/shell", shellSession);
        assertThat(page.statusCode()).as("step 1: the tab renders for a shell delegate")
            .isEqualTo(200);

        // 2. ...and names the reason, rather than drawing a terminal or an error alert.
        assertThat(page.body())
            .withFailMessage("step 2: a root-running workload does not state that a shell"
                + " is only offered where the workload runs as its own non-root user")
            .contains("data-shell-runs-as-root");
        assertThat(page.body())
            .withFailMessage("step 2: and still draws a terminal it could never connect")
            .doesNotContain("data-shell-available");

        // 3. THE GATE: the funnel refuses the same thing, by name. A hidden affordance is
        //    not a gate -- this is what the WebSocket handshake reaches.
        markRunning(rootWorkloadId);
        Throwable refused = catchThrowable(() -> new InstanceShell().open(
            rootWorkloadId, shellPrincipal, 80, 24, text -> { }, () -> { }));
        assertThat(keyOf(refused))
            .withFailMessage("step 3: a shell was NOT refused for a workload that runs as"
                + " uid 0 inside its container -- uid 0 there is uid 0 on the host, and"
                + " that gate is the entire security argument for this feature (got: "
                + refused + ")")
            .isEqualTo("shell_requires_nonroot_workload");
    }

    /**
     * GATE 3, the driver: a runtime with no pseudo-terminal lane says so as a named state,
     * exactly as the Files tab does, instead of failing mid-handshake.
     */
    @Test
    @Order(3)
    void anIncusWorkspaceStatesTheRuntimeHasNoShellLaneYet() throws Exception {
        HttpResponse<String> page = httpGet(
            "/manage/instances/" + incusWorkspaceId + "/page/shell", shellSession);
        assertThat(page.statusCode()).as("step 1: the tab renders for a shell delegate")
            .isEqualTo(200);

        // 2. The declared asymmetry is a NAMED state, never a red error alert.
        assertThat(page.body())
            .withFailMessage("step 2: an Incus workspace does not state that its runtime"
                + " has no shell lane yet")
            .contains("data-shell-unsupported");
        assertThat(page.body())
            .withFailMessage("step 2: and renders the refusal as a destructive alert")
            .doesNotContain("<pl-alert variant=\"destructive\"");

        // 3. And the funnel refuses it by that same name -- the workspace's uid is
        //    non-root, so this is the DRIVER gate answering and not gate 2 again.
        markRunning(incusWorkspaceId);
        Throwable refused = catchThrowable(() -> new InstanceShell().open(
            incusWorkspaceId, shellPrincipal, 80, 24, text -> { }, () -> { }));
        assertThat(keyOf(refused))
            .withFailMessage("step 3: an Incus workload's shell was not refused by name"
                + " (got: " + refused + ")")
            .isEqualTo("shell_unsupported_runtime");
    }

    /**
     * The capability gate on the funnel itself: a principal holding nothing is refused
     * with the SAME text as for an instance that does not exist, and an anonymous
     * (absent) principal is refused too. A refusal that names the difference is an
     * instance-existence oracle.
     */
    @Test
    @Order(4)
    void theFunnelRefusesAStrangerIdenticallyToAMissingInstanceAndRefusesNoPrincipal() {
        int missingId = rootWorkloadId + 987_654;
        assertThat(Models.get(InstanceModel.class).findById(missingId))
            .as("step 1: the control id really is absent").isNull();

        String forReal = keyOf(catchThrowable(() -> new InstanceShell().open(
            rootWorkloadId, strangerPrincipal, 80, 24, text -> { }, () -> { })));
        String forMissing = keyOf(catchThrowable(() -> new InstanceShell().open(
            missingId, strangerPrincipal, 80, 24, text -> { }, () -> { })));

        assertThat(forReal)
            .as("step 2: a stranger's shell is refused by capability")
            .isEqualTo("instance_not_permitted");
        assertThat(forReal)
            .as("step 2: and a nonexistent id answers IDENTICALLY -- no existence oracle")
            .isEqualTo(forMissing);

        // 3. No identity at all fails CLOSED. This is the case the conduit-based gate
        //    would have ALLOWED, so it is asserted directly rather than inferred.
        assertThat(keyOf(catchThrowable(() -> new InstanceShell().open(
                rootWorkloadId, null, 80, 24, text -> { }, () -> { }))))
            .withFailMessage("step 3: a shell opened with NO principal -- the socket lane"
                + " must fail closed, never fall through to a system-work allowance")
            .isEqualTo("instance_not_permitted");
    }

    /**
     * The capability is DELEGABLE and is NOT folded into the manage umbrella: a manage
     * holder does not silently acquire an interactive terminal on every record they
     * already administer.
     */
    @Test
    @Order(5)
    void manageDoesNotImplyShell() {
        int managerId = tenant("manager@shellgate.test", "Manager");
        RecordGrants.grant(GrantSubjectType.USER, managerId, InstanceModel.MODEL_ID,
            rootWorkloadId, HohenheimAccess.MANAGE, true);
        Principal manager = new UserPrincipal(managerId, "Manager");

        // 1. Positive anchor: manage really does imply the verbs it is documented to imply.
        assertThat(HohenheimAccess.hasInstanceCapability(
                manager, rootWorkloadId, HohenheimAccess.CONSOLE))
            .as("step 1: manage implies console")
            .isTrue();

        // 2. ...and does NOT reach shell. Implication is retroactive, so folding shell in
        //    would hand a terminal to every already-stored manage grant.
        assertThat(HohenheimAccess.hasInstanceCapability(
                manager, rootWorkloadId, HohenheimAccess.SHELL))
            .withFailMessage("step 2: manage implies SHELL -- that silently grants an"
                + " interactive terminal to every manage grant already in the database")
            .isFalse();

        // 3. And the funnel agrees with the walk.
        assertThat(keyOf(catchThrowable(() -> new InstanceShell().open(
                rootWorkloadId, manager, 80, 24, text -> { }, () -> { }))))
            .as("step 3: the funnel refuses a manage-only holder")
            .isEqualTo("instance_not_permitted");
    }

    // -- fixtures -------------------------------------------------------------

    private static void markRunning(int instanceId) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.findById(instanceId);
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        instances.save(row);
    }

    private static int dockerInstance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    /** A workspace on an Incus host: a NON-root uid, on the runtime with no shell lane. */
    private static int incusWorkspace(String name) {
        Model servers = Models.get(ServerModel.class);
        Row host = servers.createEmptyRow();
        host.set(ServerModel.NAME, PREFIX + "incus-host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        host.set(ServerModel.SSH_TARGET, "nobody@shellgate-incus.invalid");
        servers.save(host);
        int hostId = Models.get(ServerModel.class).findByName(PREFIX + "incus-host")
            .get(ServerModel.ID);

        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:workspace");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("start_command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        row.set(InstanceModel.SERVER_ID, hostId);
        // The seeded node-22 image; the workspace kind refuses to resolve without one.
        row.set(InstanceModel.RUNTIME_IMAGE_ID, 1);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static int tenant(String email, String name) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, name);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static String sessionOf(int userId) {
        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, (long) userId);
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);
        return session.token().secret();
    }

    /** The MACHINE KEY of a refusal; the class name for anything that is not one. */
    private static String keyOf(Throwable thrown) {
        if (!(thrown instanceof Violations violations)) {
            return thrown == null ? "" : thrown.getClass().getSimpleName();
        }
        List<Violation> all = violations.all();
        return all.isEmpty() ? "" : all.get(0).message().key();
    }
}
