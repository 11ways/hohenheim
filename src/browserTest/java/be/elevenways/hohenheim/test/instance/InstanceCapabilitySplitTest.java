package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceExec;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantAdministration;
import be.elevenways.zenit.auth.server.GrantAdministration.RecordGrantChange;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.KnownCapabilities;
import be.elevenways.zenit.common.security.RecordCapabilityDecision;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The instance capability SPLIT: console, power, config, destroy and exec are separate
 * verbs with separate enforcement, {@code manage} is the umbrella that implies the first
 * four (never exec), and the delegation boundary refuses what a holder does not hold.
 *
 * This is the Phase 3 / Phase 5 gate journey, driven as tests: "delegate console+power
 * (NOT exec, NOT config) and PROVE the delegate cannot change config, run exec or
 * delegate exec". Every refusal asserts the resulting STATE (the row did not move, no
 * grant was written) as well as the refusal identity -- a status-only check passes for a
 * refusal that already wrote the row.
 *
 * No daemon is needed: every refusal fires before any driver call, and every positive
 * anchor is asserted through the DISTINCT next-gate violation, which is what separates
 * "the capability gate let this through" from "nothing ran".
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceCapabilitySplitTest extends HohenheimTestBase {

    private static final String PREFIX = "capsplit-";

    private static int consoleUserId;
    private static int powerUserId;
    private static int managerUserId;
    private static UserPrincipal consolePrincipal;
    private static UserPrincipal powerPrincipal;
    private static UserPrincipal managerPrincipal;
    private static String consoleSession;

    private static int instanceId;

    @BeforeAll
    static void seed() {
        consoleUserId = tenant("console-only@capsplit.test", "Console Only");
        powerUserId = tenant("power-only@capsplit.test", "Power Only");
        managerUserId = tenant("manager@capsplit.test", "Manager");
        consolePrincipal = new UserPrincipal(consoleUserId, "Console Only");
        powerPrincipal = new UserPrincipal(powerUserId, "Power Only");
        managerPrincipal = new UserPrincipal(managerUserId, "Manager");

        Session session = Zenit.getSessionStore().create();
        session.set(be.elevenways.zenit.auth.AuthKeys.USER_ID, (long) consoleUserId);
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);
        consoleSession = session.token().secret();

        instanceId = instance(PREFIX + "alpha");

        // The delegation under test: console + power, and NOTHING else.
        RecordGrants.grant("user", consoleUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONSOLE, true);
        RecordGrants.grant("user", powerUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.POWER, true);
        RecordGrants.grant("user", managerUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX))
                .withTrashed().all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
    }

    // -- fixtures -------------------------------------------------------------

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

    private static int instance(String name) {
        Model instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static AccessContext contextOf(UserPrincipal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    /** The delegation-refusal IDENTITY: GrantAdministration keys every refusal "refused". */
    private static String refusalTargets(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        StringBuilder targets = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            targets.append(violation.message().filters().get("target")).append(' ');
        }
        return targets.toString();
    }

    private static Row storedInstance() {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId)).withTrashed().first();
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    // -- the journeys ---------------------------------------------------------

    /**
     * The vocabulary is DECLARED the way the gates need it, and the declarations that
     * matter for security are structural rather than conventional.
     */
    @Test
    @Order(1)
    void theVocabularyDeclaresExecUnreachableAndTheNarrowVerbsDelegable() {
        // Step 1: every narrow verb the gates name exists and is delegable, so an
        // operator can hand out console ALONE.
        for (String capability : List.of(HohenheimAccess.VIEW, HohenheimAccess.CONSOLE,
                HohenheimAccess.POWER, HohenheimAccess.CONFIG, HohenheimAccess.DESTROY)) {
            assertThat(KnownCapabilities.get(InstanceModel.MODEL_ID, capability))
                .as("step 1: " + capability + " must be a registered instance capability")
                .isNotNull();
            assertThat(KnownCapabilities.isDelegable(InstanceModel.MODEL_ID, capability))
                .as("step 1: " + capability + " must be delegable")
                .isTrue();
        }

        // Step 2: exec is registered (so it is refusable by name rather than unknown)
        // and is NOT delegable -- the dyndns standard applied to a host-escape verb.
        assertThat(KnownCapabilities.get(InstanceModel.MODEL_ID, HohenheimAccess.EXEC))
            .as("step 2: exec must be a registered capability").isNotNull();
        assertThat(KnownCapabilities.isDelegable(InstanceModel.MODEL_ID, HohenheimAccess.EXEC))
            .as("step 2: exec must never be delegable").isFalse();
        assertThat(KnownCapabilities.isOwnerImplied(InstanceModel.MODEL_ID, HohenheimAccess.EXEC))
            .as("step 2: exec must never be owner-implied").isFalse();

        // Step 3 (LOAD-BEARING): exec is implied by NOTHING. This is the clause the
        // Phase 3 gate rests on, and KnownCapability enforces it structurally -- an
        // ADMIN capability cannot even carry an impliedBy set.
        assertThat(KnownCapabilities.impliersOf(InstanceModel.MODEL_ID, HohenheimAccess.EXEC))
            .as("step 3: no capability may imply exec").isEmpty();

        // Step 4: manage IS the umbrella over exactly the five verbs that rode it before
        // the split -- not over files/snapshots/backups/image_any, which would silently
        // widen every already-stored manage row.
        assertThat(KnownCapabilities.impliersOf(InstanceModel.MODEL_ID, HohenheimAccess.CONSOLE))
            .as("step 4: console is implied by manage").containsExactly(HohenheimAccess.MANAGE);
        for (String untouched : List.of(HohenheimAccess.FILES_READ, HohenheimAccess.FILES_WRITE,
                HohenheimAccess.SNAPSHOTS, HohenheimAccess.BACKUPS, HohenheimAccess.IMAGE_ANY)) {
            assertThat(KnownCapabilities.impliersOf(InstanceModel.MODEL_ID, untouched))
                .as("step 4: " + untouched + " must NOT be reachable through manage")
                .isEmpty();
        }
    }

    /**
     * POSITIVE ANCHOR + the umbrella: a manage grant keeps exactly the authority it had
     * before the split, through the framework's implied-grant row -- so no stored row
     * needed migrating.
     */
    @Test
    @Order(2)
    void aManageGrantStillCarriesTheFiveVerbsItAlwaysDidAndNothingMore() {
        AccessContext manager = contextOf(managerPrincipal);

        // Step 1: the umbrella row DECIDES it -- not a grant row, which is what proves
        // the implication is doing the work rather than a leftover fixture.
        for (String implied : List.of(HohenheimAccess.VIEW, HohenheimAccess.CONSOLE,
                HohenheimAccess.POWER, HohenheimAccess.CONFIG, HohenheimAccess.DESTROY)) {
            assertThat(manager.capabilityDecision(InstanceModel.MODEL_ID, instanceId, implied))
                .as("step 1: manage must imply " + implied + " through the umbrella row")
                .isEqualTo(RecordCapabilityDecision.IMPLIED_GRANT);
        }

        // Step 2: and manage still decides itself by its own grant.
        assertThat(manager.capabilityDecision(InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE))
            .as("step 2: manage itself is a plain positive grant")
            .isEqualTo(RecordCapabilityDecision.GRANT_ALLOWED);

        // Step 3 (LOAD-BEARING): manage does NOT reach exec, nor the artifact and file
        // verbs it never carried.
        for (String outside : List.of(HohenheimAccess.EXEC, HohenheimAccess.SNAPSHOTS,
                HohenheimAccess.BACKUPS, HohenheimAccess.FILES_READ, HohenheimAccess.FILES_WRITE)) {
            assertThat(manager.hasCapability(InstanceModel.MODEL_ID, instanceId, outside))
                .as("step 3: manage must NOT confer " + outside).isFalse();
        }

        // Step 4: an explicit DENY of a narrow verb beats the umbrella, so an operator
        // can hand out manage-minus-one.
        RecordGrants.grant("user", managerUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.DESTROY, false);
        assertThat(contextOf(managerPrincipal)
                .capabilityDecision(InstanceModel.MODEL_ID, instanceId, HohenheimAccess.DESTROY))
            .as("step 4: an explicit deny must beat the manage umbrella")
            .isEqualTo(RecordCapabilityDecision.GRANT_DENIED);
        RecordGrants.revoke("user", managerUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.DESTROY);
        assertThat(contextOf(managerPrincipal)
                .hasCapability(InstanceModel.MODEL_ID, instanceId, HohenheimAccess.DESTROY))
            .as("step 4: revoking the deny restores the umbrella exactly").isTrue();
    }

    /** POSITIVE ANCHOR: the console-only delegate really can see and console the box. */
    @Test
    @Order(3)
    void theConsoleDelegateCanSeeTheInstanceAndReachItsConsole() throws Exception {
        AccessContext delegate = contextOf(consolePrincipal);

        // Step 1: console implies view, so the delegate holds view WITHOUT anyone having
        // granted it -- the operator never has to remember a second checkbox.
        assertThat(delegate.capabilityDecision(InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.VIEW))
            .as("step 1: console must imply view")
            .isEqualTo(RecordCapabilityDecision.IMPLIED_GRANT);

        // Step 2: which is what makes /manage reachable and the record listed. The list
        // CONTENT is the assertion; a status-only check would pass on an empty list.
        HttpResponse<String> list = get("/manage/instances", consoleSession);
        assertThat(list.statusCode()).as("step 2: the scoped list renders").isEqualTo(200);
        assertThat(list.body())
            .as("step 2: a console-only delegate sees the instance it may console")
            .contains(PREFIX + "alpha");

        // Step 3: the console WebSocket's own principal-only walk (no conduit at open
        // time) answers the same way.
        assertThat(HohenheimAccess.hasInstanceCapability(
                consolePrincipal, instanceId, HohenheimAccess.CONSOLE))
            .as("step 3: the principal-only console walk allows the delegate").isTrue();

        // Step 4: and the console COMMAND funnel passes the capability gate -- the
        // refusal that follows is the console session's, not the gate's. Without this
        // anchor every refusal below would also pass on a path that simply never runs.
        Throwable sent = catchThrowable(() -> TenantConduits.as(consolePrincipal,
            () -> InstanceConsoles.sendCommand(instanceId, "say hello")));
        assertThat(violationKeys(sent))
            .as("step 4: the console gate is passed; the refusal is the session's")
            .doesNotContain("instance_not_permitted");
    }

    /**
     * The refusal half of the Phase 3 gate: the console delegate cannot change config,
     * destroy or exec -- asserted on the resulting STATE, not on a status code.
     */
    @Test
    @Order(4)
    void theConsoleDelegateCannotChangeConfigDestroyOrExec() throws Exception {
        String originalName = storedInstance().get(InstanceModel.NAME);

        // Step 1: CONFIG. The write pipeline is the enforcement point (a form that omits
        // a field is an affordance, not a gate), so the write is driven straight at the
        // model under the delegate's identity -- the shape a direct POST or a revision
        // restore would take.
        Throwable configEdit = catchThrowable(() -> TenantConduits.as(consolePrincipal, () -> {
            Row row = Models.get(InstanceModel.class).findById(instanceId);
            row.set(InstanceModel.NAME, PREFIX + "seized");
            Models.get(InstanceModel.class).save(row);
        }));
        assertThat(violationKeys(configEdit))
            .as("step 1: a console-only delegate is refused a config edit")
            .contains("instance_not_permitted");
        assertThat(storedInstance().get(InstanceModel.NAME))
            .as("step 1 (STATE): the refusal must not have written the row")
            .isEqualTo(originalName);

        // Step 2: DESTROY. The record must still be live afterwards -- a refusal that
        // stamped deleted_at would pass a violation-only assertion.
        Throwable destroy = catchThrowable(() ->
            TenantConduits.as(consolePrincipal, () -> new InstanceService().destroy(instanceId)));
        assertThat(violationKeys(destroy))
            .as("step 2: a console-only delegate is refused a destroy")
            .contains("instance_not_permitted");
        assertThat(storedInstance().get(InstanceModel.DELETED_AT))
            .as("step 2 (STATE): the instance must still be live").isNull();

        // Step 3: POWER. Console is not power; the delegate cannot start the workload.
        Throwable deploy = catchThrowable(() ->
            TenantConduits.as(consolePrincipal, () -> new InstanceService().deploy(instanceId)));
        assertThat(violationKeys(deploy))
            .as("step 3: console does not carry power")
            .contains("instance_not_permitted");

        // Step 4: EXEC. The clause the gate calls "cannot run exec" -- and it is a REAL
        // refusal now, because the exec surface exists and enforces its own capability.
        Throwable exec = catchThrowable(() ->
            TenantConduits.as(consolePrincipal, () -> new InstanceExec().run(instanceId, "id")));
        assertThat(violationKeys(exec))
            .as("step 4: a console-only delegate is refused an exec")
            .contains("instance_not_permitted");
        assertThat(contextOf(consolePrincipal)
                .hasCapability(InstanceModel.MODEL_ID, instanceId, HohenheimAccess.EXEC))
            .as("step 4: and the walk agrees the delegate holds no exec").isFalse();

        // Step 5: the exec TAB is not reachable either -- the admin panel gate answers
        // first here, which is stated rather than relied on: the enforcing check is the
        // funnel in step 4 plus InstanceExecPage.visibleFor.
        assertThat(get("/admin/instances/" + instanceId + "/page/exec", consoleSession)
                .statusCode())
            .as("step 5: the exec tab is not reachable for the delegate")
            .isNotEqualTo(200);
    }

    /**
     * The delegation half: a console-only delegate cannot pass on what it does not hold,
     * and NOBODY below admin can pass on exec -- asserted on the stored rows, because a
     * refusal that still wrote the grant would pass a message-only test.
     */
    @Test
    @Order(5)
    void theConsoleDelegateCannotDelegateConfigDestroyOrExec() {
        AccessContext delegate = contextOf(consolePrincipal);

        // Step 1: what it HOLDS, it may pass on -- the positive anchor without which
        // every refusal below could be explained by a broken boundary.
        List<RecordGrantChange> passOnConsole = List.of(new RecordGrantChange(
            "user", powerUserId, HohenheimAccess.CONSOLE, Boolean.TRUE));
        assertThat(GrantAdministration.requireAuthorizedRecordDiff(delegate,
                InstanceModel.MODEL_ID, instanceId, "grants", passOnConsole))
            .as("step 1: a console holder may delegate console")
            .hasSize(1);

        // Step 2: CONFIG -- refused for lack of the capability itself.
        Throwable config = catchThrowable(() -> GrantAdministration.requireAuthorizedRecordDiff(
            delegate, InstanceModel.MODEL_ID, instanceId, "grants",
            List.of(new RecordGrantChange("user", powerUserId, HohenheimAccess.CONFIG,
                Boolean.TRUE))));
        assertThat(refusalTargets(config))
            .as("step 2: refused because the actor does not hold config")
            .contains("record_grant");

        // Step 3: DESTROY -- same refusal identity.
        Throwable destroy = catchThrowable(() -> GrantAdministration.requireAuthorizedRecordDiff(
            delegate, InstanceModel.MODEL_ID, instanceId, "grants",
            List.of(new RecordGrantChange("user", powerUserId, HohenheimAccess.DESTROY,
                Boolean.TRUE))));
        assertThat(refusalTargets(destroy))
            .as("step 3: refused because the actor does not hold destroy")
            .contains("record_grant");

        // Step 4 (LOAD-BEARING): EXEC is refused for a DIFFERENT reason -- not
        // delegable -- which is what makes it unreachable even for a holder.
        Throwable exec = catchThrowable(() -> GrantAdministration.requireAuthorizedRecordDiff(
            delegate, InstanceModel.MODEL_ID, instanceId, "grants",
            List.of(new RecordGrantChange("user", powerUserId, HohenheimAccess.EXEC,
                Boolean.TRUE))));
        assertThat(refusalTargets(exec))
            .as("step 4: exec is refused as NON-DELEGABLE, not merely as unheld")
            .contains("record_delegate");

        // Step 5: the same refusal for the widest non-admin authority there is. This is
        // the Phase 5 gate clause: no per-record grant administrator, however broad, can
        // mint exec. (There is deliberately no separate "access.manage" capability --
        // the per-record boundary IS holding a delegable capability, and it is strictly
        // narrower than a dedicated one would be.)
        Throwable byManager = catchThrowable(() -> GrantAdministration.requireAuthorizedRecordDiff(
            contextOf(managerPrincipal), InstanceModel.MODEL_ID, instanceId, "grants",
            List.of(new RecordGrantChange("user", powerUserId, HohenheimAccess.EXEC,
                Boolean.TRUE))));
        assertThat(refusalTargets(byManager))
            .as("step 5: even a manage holder cannot mint exec")
            .contains("record_delegate");

        // Step 6 (STATE): not one of those refusals wrote a row.
        for (String capability : List.of(HohenheimAccess.CONFIG, HohenheimAccess.DESTROY,
                HohenheimAccess.EXEC)) {
            assertThat(contextOf(powerPrincipal)
                    .hasCapability(InstanceModel.MODEL_ID, instanceId, capability))
                .as("step 6 (STATE): no refused delegation may have granted " + capability)
                .isFalse();
        }
    }

    /** The power-only delegate: the mirror journey, so neither verb is a synonym. */
    @Test
    @Order(6)
    void thePowerDelegateCanPowerButCannotConsole() {
        // Step 1: POSITIVE ANCHOR -- the power gate is passed and the refusal that
        // follows belongs to host admission, a DIFFERENT gate entirely.
        Throwable deploy = catchThrowable(() ->
            TenantConduits.as(powerPrincipal, () -> new InstanceService().deploy(instanceId)));
        assertThat(violationKeys(deploy))
            .as("step 1: a power holder passes the capability gate")
            .doesNotContain("instance_not_permitted");

        // Step 2: and cannot reach the console, by either face of the walk.
        assertThat(HohenheimAccess.hasInstanceCapability(
                powerPrincipal, instanceId, HohenheimAccess.CONSOLE))
            .as("step 2: power does not carry console (principal-only walk)").isFalse();
        Throwable command = catchThrowable(() -> TenantConduits.as(powerPrincipal,
            () -> InstanceConsoles.sendCommand(instanceId, "say hello")));
        assertThat(violationKeys(command))
            .as("step 2: the console command funnel refuses a power-only holder")
            .contains("instance_not_permitted");

        // Step 3: power still implies view, so the delegate is not locked out of the
        // page carrying the button.
        assertThat(contextOf(powerPrincipal)
                .hasCapability(InstanceModel.MODEL_ID, instanceId, HohenheimAccess.VIEW))
            .as("step 3: power implies view").isTrue();

        // Step 4: and it does not carry config -- the row must not move.
        String originalName = storedInstance().get(InstanceModel.NAME);
        Throwable edit = catchThrowable(() -> TenantConduits.as(powerPrincipal, () -> {
            Row row = Models.get(InstanceModel.class).findById(instanceId);
            row.set(InstanceModel.NAME, PREFIX + "power-seized");
            Models.get(InstanceModel.class).save(row);
        }));
        assertThat(violationKeys(edit))
            .as("step 4: power does not carry config").contains("instance_not_permitted");
        assertThat(storedInstance().get(InstanceModel.NAME))
            .as("step 4 (STATE): the row did not move").isEqualTo(originalName);
    }

    /**
     * ADMIN ANCHOR: an operator can still do everything, including exec and the config
     * edit the delegates were refused. Without this the whole suite would pass on a
     * completely broken instance tier.
     */
    @Test
    @Order(7)
    void anAdministratorStillHoldsEveryVerbIncludingExec() {
        // The suite's seeded admin holds "*", which is the hohenheim admin permission.
        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        assertThat(admin).as("the seeded admin must exist").isNotNull();
        AccessContext operator = contextOf(
            new UserPrincipal(admin.get(UserModel.ID), "Test Admin"));

        // Step 1: every registered capability, exec included, resolves through the ADMIN
        // bypass row.
        for (String capability : List.of(HohenheimAccess.MANAGE, HohenheimAccess.VIEW,
                HohenheimAccess.CONSOLE, HohenheimAccess.POWER, HohenheimAccess.CONFIG,
                HohenheimAccess.DESTROY, HohenheimAccess.EXEC, HohenheimAccess.SNAPSHOTS,
                HohenheimAccess.BACKUPS, HohenheimAccess.FILES_READ,
                HohenheimAccess.FILES_WRITE, HohenheimAccess.IMAGE_ANY)) {
            assertThat(operator.capabilityDecision(InstanceModel.MODEL_ID, instanceId, capability))
                .as("step 1: an admin holds " + capability + " by bypass")
                .isEqualTo(RecordCapabilityDecision.ADMIN_BYPASS);
        }

        // Step 2: and the tenant-write invariant never fires for an operator, so the
        // config edit the delegates were refused genuinely lands.
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        row.set(InstanceModel.NAME, PREFIX + "renamed-by-admin");
        Models.get(InstanceModel.class).save(row);
        assertThat(storedInstance().get(InstanceModel.NAME))
            .as("step 2 (STATE): the operator's edit landed")
            .isEqualTo(PREFIX + "renamed-by-admin");

        // Step 3: an admin may plant exec deliberately (the operator choice the plan
        // describes) -- and the recipient STILL cannot pass it on.
        RecordGrants.grant("user", consoleUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.EXEC, true);
        assertThat(contextOf(consolePrincipal)
                .hasCapability(InstanceModel.MODEL_ID, instanceId, HohenheimAccess.EXEC))
            .as("step 3: an operator-planted exec grant is effective").isTrue();
        Throwable relay = catchThrowable(() -> GrantAdministration.requireAuthorizedRecordDiff(
            contextOf(consolePrincipal), InstanceModel.MODEL_ID, instanceId, "grants",
            List.of(new RecordGrantChange("user", powerUserId, HohenheimAccess.EXEC,
                Boolean.TRUE))));
        assertThat(refusalTargets(relay))
            .as("step 3: a HOLDER of exec still cannot re-delegate it")
            .contains("record_delegate");
        assertThat(contextOf(powerPrincipal)
                .hasCapability(InstanceModel.MODEL_ID, instanceId, HohenheimAccess.EXEC))
            .as("step 3 (STATE): and no exec row was written for the third party").isFalse();
        RecordGrants.revoke("user", consoleUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.EXEC);
    }
}
