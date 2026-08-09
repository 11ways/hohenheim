package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceLogModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The /manage instance projection: what a delegated tenant may SEE and DO with the
 * instance tier, and -- the load-bearing half -- what a second tenant may not.
 *
 * No daemon is needed: every refusal proven here fires before any driver call, and the
 * successful paths are record writes. Power actions are asserted through the VIOLATION
 * KEY they refuse with, which is what separates "the capability gate stopped this" from
 * "the host would not have taken it anyway".
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantInstanceSurfaceTest extends HohenheimTestBase {

    private static final String PREFIX = "tenant-surface-";

    private static Integer tenantAId;
    private static Integer tenantBId;
    private static String sessionA;
    private static String csrfA;
    private static UserPrincipal principalA;

    private static Integer instanceAId;
    private static Integer instanceBId;
    private static Integer approvedTemplateId;
    private static Integer unapprovedTemplateId;
    private static Integer admittedHostId;
    private static Integer previousLimit;

    @BeforeAll
    static void seed() {
        tenantAId = tenant("tenant-a@surface.test", "Tenant A");
        tenantBId = tenant("tenant-b@surface.test", "Tenant B");
        principalA = new UserPrincipal(tenantAId, "Tenant A");

        Session session = Zenit.getSessionStore().create();
        session.set(be.elevenways.zenit.auth.AuthKeys.USER_ID, tenantAId.longValue());
        csrfA = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrfA);
        Zenit.getSessionStore().save(session);
        sessionA = session.id();

        instanceAId = instance(PREFIX + "alpha");
        instanceBId = instance(PREFIX + "bravo");
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantBId, InstanceModel.MODEL_ID, instanceBId,
            HohenheimAccess.MANAGE, true);

        approvedTemplateId = template(PREFIX + "approved", true);
        unapprovedTemplateId = template(PREFIX + "unapproved", false);

        previousLimit = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER);
    }

    @AfterAll
    static void cleanUp() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER,
            previousLimit == null ? 0 : previousLimit);
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            for (Row schedule : Models.get(RecordScheduleModel.class)
                    .findForRecord(InstanceModel.MODEL_ID, row.get(InstanceModel.ID))) {
                Models.get(RecordScheduleModel.class).delete(schedule.get(RecordScheduleModel.ID));
            }
            instances.delete(row.get(InstanceModel.ID));
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            templates.delete(row.get(InstanceTemplateModel.ID));
        }
        if (admittedHostId != null) {
            Models.get(ServerModel.class).delete(admittedHostId);
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

    private static int template(String name, boolean approved) {
        Model templates = Models.get(InstanceTemplateModel.class);
        Row row = templates.createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        if (approved) {
            row.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
            row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        }
        templates.save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> tenantGet(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionA)
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> tenantPost(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionA)
            .header("X-Csrf-Token", csrfA)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    // -- the journeys ---------------------------------------------------------

    /** A manage grant on ONE instance opens /manage and shows exactly that instance. */
    @Test
    @Order(1)
    void oneGrantOpensTheProjectionAndNeverShowsAnotherTenantsInstance() throws Exception {
        // 1. Panel eligibility comes from the INSTANCE grant alone: tenant A owns no
        //    site at all, and /manage is the panel built for exactly this tenant.
        assertThat(tenantGet("/manage").statusCode())
            .as("step 1: an instance-only grant makes /manage reachable")
            .isIn(200, 302, 303);

        // 2. The list CONTENT is the assertion, not the status: a leak here is a name
        //    in the body, and a status-only check would pass while leaking.
        HttpResponse<String> list = tenantGet("/manage/instances");
        assertThat(list.statusCode()).as("step 2: the scoped list renders").isEqualTo(200);
        assertThat(list.body())
            .as("step 2: tenant A's own instance is listed")
            .contains(PREFIX + "alpha");
        assertThat(list.body())
            .as("step 2: tenant B's instance NEVER appears in tenant A's list")
            .doesNotContain(PREFIX + "bravo");

        // 3. The instance-tier admin panel stays shut.
        assertThat(tenantGet("/admin/instances").statusCode())
            .as("step 3: the admin surface refuses the tenant").isEqualTo(403);

        // 4. The tenant's own detail page renders; the execution and placement fields
        //    are absent from the delegated form.
        HttpResponse<String> detail = tenantGet("/manage/instances/" + instanceAId);
        assertThat(detail.statusCode()).as("step 4: the tenant's own record opens").isEqualTo(200);
        assertThat(detail.body())
            .as("step 4: kind, settings and the host pick are not authorable here")
            .doesNotContain("name=\"kind\"")
            .doesNotContain("name=\"settings.image\"")
            .doesNotContain("name=\"server_id\"");
    }

    /** COUNTERFACTUAL: an unowned id and a nonexistent id are indistinguishable. */
    @Test
    @Order(2)
    void probingAnUnownedIdAnswersExactlyLikeAnIdThatDoesNotExist() throws Exception {
        int absentId = 900_000_000;
        assertThat(Models.get(InstanceModel.class).findById(absentId))
            .as("step 1: the control id really does not exist").isNull();

        HttpResponse<String> foreign = tenantGet("/manage/instances/" + instanceBId);
        HttpResponse<String> absent = tenantGet("/manage/instances/" + absentId);

        assertThat(foreign.statusCode())
            .as("step 2: an instance owned by tenant B reads as MISSING, not forbidden")
            .isEqualTo(404);
        assertThat(absent.statusCode()).as("step 2: as does an id nobody owns").isEqualTo(404);
        assertThat(foreign.statusCode())
            .as("step 3: the two answers are the same status")
            .isEqualTo(absent.statusCode());
        assertThat(foreign.body())
            .as("step 3: and the same body -- no existence oracle")
            .isEqualTo(absent.body());
        assertThat(foreign.body())
            .as("step 3: and neither carries the other tenant's instance name")
            .doesNotContain(PREFIX + "bravo");

        // 4. The subpages carry the same scope; a tab is not a side door. The CONTENT is
        //    the assertion here, exactly as it is for the record itself: the answer must
        //    be the SAME one an id nobody owns gets, so a tab can never become the
        //    existence oracle the detail page is not. (The name check below is only a
        //    backstop -- today's refusal is a generic envelope that structurally carries
        //    no record data, and a bare doesNotContain against it would prove nothing.)
        for (String tab : new String[] {"console", "provisioning", "schedules"}) {
            HttpResponse<String> foreignTab =
                tenantGet("/manage/instances/" + instanceBId + "/page/" + tab);
            HttpResponse<String> absentTab =
                tenantGet("/manage/instances/" + absentId + "/page/" + tab);
            assertThat(foreignTab.statusCode())
                .as("step 4: the " + tab + " tab of a foreign instance is missing too")
                .isEqualTo(404);
            assertThat(foreignTab.body())
                .as("step 4: and the " + tab + " tab answers BYTE-IDENTICALLY to a tab of"
                    + " an id nobody owns -- no per-record detail reaches the refusal")
                .isEqualTo(absentTab.body());
            assertThat(foreignTab.body())
                .as("step 4: which in particular never carries tenant B's name")
                .doesNotContain(PREFIX + "bravo");
        }
    }

    /** COUNTERFACTUAL: revoking between render and act refuses the act. */
    @Test
    @Order(3)
    void revokingTheGrantBetweenRenderAndActRefusesTheAction() {
        // 1. With the grant, the power funnel gets PAST the capability gate and is
        //    stopped by the NEXT gate instead -- the local host is not admitted for
        //    instance placement. The distinct violation key is the evidence that the
        //    capability check passed rather than that nothing ran.
        Throwable withGrant = catchThrowable(() ->
            TenantConduits.as(principalA, () -> new InstanceService().deploy(instanceAId)));
        assertThat(violationKeys(withGrant))
            .as("step 1: a manage holder passes the capability gate and meets host admission")
            .contains("host_not_admitted")
            .doesNotContain("instance_not_permitted");

        // 2. Revoke, then act again with NOTHING else changed: the refusal moves to the
        //    capability gate. Rendering already happened in step 1 of the previous test;
        //    what this pins is that the gate is asked at EXECUTION, every time.
        RecordGrants.revoke("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.MANAGE);
        Throwable revoked = catchThrowable(() ->
            TenantConduits.as(principalA, () -> new InstanceService().deploy(instanceAId)));
        assertThat(violationKeys(revoked))
            .as("step 2: with the grant gone the SAME call is refused by the capability gate")
            .contains("instance_not_permitted");

        // 3. The walk itself agrees, through the principal-only face the WebSocket
        //    console uses -- the same decision, asked without a conduit.
        assertThat(HohenheimAccess.canManageInstance(principalA, instanceAId))
            .as("step 3: the principal-only walk answers no as well").isFalse();

        // 4. Restore for the later ordered tests.
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.MANAGE, true);
        Throwable restored = catchThrowable(() ->
            TenantConduits.as(principalA, () -> new InstanceService().deploy(instanceAId)));
        assertThat(violationKeys(restored))
            .as("step 4: re-granting restores the pre-revocation refusal exactly")
            .contains("host_not_admitted");
    }

    /** Snapshot and backup answer to their OWN capabilities, not to manage. */
    @Test
    @Order(4)
    void artifactActionsDemandTheirOwnCapabilityAndScopeTheirLists() throws Exception {
        ensureManageGrant();
        // 1. manage alone does not buy a snapshot.
        Throwable noSnapshots = catchThrowable(() -> TenantConduits.as(principalA,
            () -> new be.elevenways.hohenheim.server.instance.InstanceSnapshots()
                .create(instanceAId, null)));
        assertThat(violationKeys(noSnapshots))
            .as("step 1: a manage-only holder cannot snapshot")
            .contains("instance_not_permitted");

        // 2. With the snapshots capability the SAME call passes the gate and is stopped
        //    by the driver-level requirement instead.
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.SNAPSHOTS, true);
        Throwable withSnapshots = catchThrowable(() -> TenantConduits.as(principalA,
            () -> new be.elevenways.hohenheim.server.instance.InstanceSnapshots()
                .create(instanceAId, null)));
        assertThat(violationKeys(withSnapshots))
            .as("step 2: the capability gate is passed; the refusal is now the driver's")
            .doesNotContain("instance_not_permitted");

        // 3. And it never reaches ANOTHER tenant's instance, capability or not.
        Throwable foreign = catchThrowable(() -> TenantConduits.as(principalA,
            () -> new be.elevenways.hohenheim.server.instance.InstanceSnapshots()
                .create(instanceBId, null)));
        assertThat(violationKeys(foreign))
            .as("step 3: snapshots on tenant B's instance stay refused")
            .contains("instance_not_permitted");

        // 4. The delegated backup list is scoped by the BACKUPS capability, which this
        //    tenant does not hold: the page renders, with nothing in it.
        HttpResponse<String> backups = tenantGet("/manage/instance-backups");
        assertThat(backups.statusCode()).as("step 4: the scoped list renders").isEqualTo(200);
        assertThat(backups.body())
            .as("step 4: and carries neither tenant's instance names")
            .doesNotContain(PREFIX + "alpha").doesNotContain(PREFIX + "bravo");
    }

    /** Cross-tenant schedules: a schedule of B's instance is invisible to A. */
    @Test
    @Order(5)
    void schedulesOfAnotherTenantsInstanceAreNotListed() throws Exception {
        ensureManageGrant();
        Model schedules = Models.get(RecordScheduleModel.class);
        Row mine = schedules.createEmptyRow();
        mine.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        mine.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceAId));
        mine.set(RecordScheduleModel.NAME, PREFIX + "mine");
        mine.set(RecordScheduleModel.CRON, "0 4 * * *");
        mine.set(RecordScheduleModel.ENABLED, true);
        schedules.save(mine);

        Row theirs = schedules.createEmptyRow();
        theirs.set(RecordScheduleModel.MODEL, InstanceModel.MODEL_ID.toString());
        theirs.set(RecordScheduleModel.RECORD_ID, String.valueOf(instanceBId));
        theirs.set(RecordScheduleModel.NAME, PREFIX + "theirs");
        theirs.set(RecordScheduleModel.CRON, "0 5 * * *");
        theirs.set(RecordScheduleModel.ENABLED, true);
        schedules.save(theirs);

        HttpResponse<String> list = tenantGet("/manage/instance-schedules");
        assertThat(list.statusCode()).as("step 1: the scoped schedule list renders").isEqualTo(200);
        assertThat(list.body())
            .as("step 1: tenant A sees the schedule of its own instance")
            .contains(PREFIX + "mine");
        assertThat(list.body())
            .as("step 1: and never the schedule of tenant B's instance")
            .doesNotContain(PREFIX + "theirs");

        HttpResponse<String> foreign = tenantGet("/manage/instance-schedules/"
            + theirs.get(RecordScheduleModel.ID));
        assertThat(foreign.statusCode())
            .as("step 2: opening it directly reads as missing").isEqualTo(404);
        assertThat(foreign.body())
            .as("step 2: and the refusal body never names tenant B's instance")
            .doesNotContain(PREFIX + "theirs");

        schedules.delete(theirs.get(RecordScheduleModel.ID));
        schedules.delete(mine.get(RecordScheduleModel.ID));
    }

    /**
     * The creation-authority decision end to end: permission, placement, ownership and
     * a per-TENANT quota bucket.
     */
    @Test
    @Order(6)
    void creationNeedsAuthorityAndPlacementAndChargesTheTenantsOwnQuota() throws Exception {
        ensureManageGrant();
        String createUrl = "/instances/from-template";

        // 1. The catalog offers only APPROVED templates, and only those.
        HttpResponse<String> catalog = tenantGet("/manage/instance-templates");
        assertThat(catalog.statusCode()).as("step 1: the tenant catalog renders").isEqualTo(200);
        assertThat(catalog.body()).as("step 1: an approved template is offered")
            .contains(PREFIX + "approved");
        assertThat(catalog.body()).as("step 1: an unapproved one is not even visible")
            .doesNotContain(PREFIX + "unapproved");

        // 2. Without hohenheim.instances.create the submit is refused BY NAME, and
        //    nothing persists.
        HttpResponse<String> unauthorized = tenantPost(createUrl,
            "template_id=" + approvedTemplateId + "&name=" + PREFIX + "created");
        assertThat(unauthorized.body())
            .as("step 2: a tenant without create authority is refused, named")
            .contains("You are not allowed to create instances");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "created")).count())
            .as("step 2: the refused create persisted NOTHING").isZero();

        // 3. With the permission but NO admitted host, placement refuses -- it never
        //    falls back to the local daemon, which is the whole point of the decision.
        GrantService.createDirectGrant("user", tenantAId,
            HohenheimAccess.INSTANCES_CREATE.value(), true);
        HttpResponse<String> nowhere = tenantPost(createUrl,
            "template_id=" + approvedTemplateId + "&name=" + PREFIX + "created");
        assertThat(nowhere.body())
            .as("step 3: no admitted host means a NAMED placement refusal")
            .contains("No admitted host currently accepts this workload");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "created")).count())
            .as("step 3: still nothing persisted").isZero();

        // 4. An operator admits a host with a posture that accepts hostile containers.
        //    The tenant NAMES A DIFFERENT HOST in the submit; placement ignores it.
        admittedHostId = admittedHost();
        HttpResponse<String> created = tenantPost(createUrl,
            "template_id=" + approvedTemplateId + "&name=" + PREFIX + "created"
                + "&server_id=" + ServerModel.localServerId());
        assertThat(created.statusCode()).as("step 4: the create lands").isIn(302, 303);
        Row instance = Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.eq(PREFIX + "created")).first();
        assertThat(instance).as("step 4: the instance exists").isNotNull();
        assertThat((Integer) instance.get(InstanceModel.SERVER_ID))
            .as("step 4: it landed on the ADMITTED host, not the one the tenant named")
            .isEqualTo(admittedHostId);

        // 5. The creator owns it: a manage grant exists, so the record is reachable on
        //    the very surface that created it. A create whose result the creator cannot
        //    see would be a create in name only.
        assertThat(HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID,
                instance.get(InstanceModel.ID)))
            .as("step 5: the creator holds manage on what it created")
            .contains("user:" + tenantAId);
        assertThat(tenantGet("/manage/instances").body())
            .as("step 5: and it shows up in the tenant's own list")
            .contains(PREFIX + "created");

        // 6. The QUOTA charged the TENANT's bucket, not the operator's -- the whole
        //    reason a per-owner cap can bind a self-service create at all.
        String tenantBucket = InstanceQuota.bucketKeyOf(
            HohenheimAccess.packSubjects(java.util.Set.of("user:" + tenantAId)));
        assertThat((String) instance.get(InstanceModel.QUOTA_BUCKET))
            .as("step 6: the create is charged to the creating tenant's bucket")
            .isEqualTo(tenantBucket);
        assertThat((String) instance.get(InstanceModel.QUOTA_BUCKET))
            .as("step 6: and emphatically NOT to the shared operator bucket")
            .isNotEqualTo(InstanceQuota.bucketKeyOf(""));

        // 7. And the cap binds: set the tenant's limit to what it already uses and the
        //    next create is refused by name.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER,
            (int) InstanceQuota.usedBy(HohenheimAccess.packSubjects(
                java.util.Set.of("user:" + tenantAId))));
        HttpResponse<String> capped = tenantPost(createUrl,
            "template_id=" + approvedTemplateId + "&name=" + PREFIX + "over-cap");
        assertThat(capped.body())
            .as("step 7: the per-owner cap refuses the next create, named")
            .contains("Instance quota reached");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "over-cap")).count())
            .as("step 7: and nothing landed").isZero();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_INSTANCES_PER_OWNER, 0);

        // 8. An UNAPPROVED template stays unusable even by direct id: the catalog's
        //    omission is UX, the funnel is the gate.
        HttpResponse<String> unapproved = tenantPost(createUrl,
            "template_id=" + unapprovedTemplateId + "&name=" + PREFIX + "sneaky");
        assertThat(unapproved.body())
            .as("step 8: an unapproved template is refused, named")
            .contains("is not approved for use yet");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "sneaky")).count())
            .as("step 8: nothing landed").isZero();
    }

    /**
     * Re-assert tenant A's manage grant. The ordered journeys share a server, and one
     * that revokes mid-way (or aborts before restoring) must not decide what the next
     * one is testing.
     */
    private static void ensureManageGrant() {
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.MANAGE, true);
    }

    /**
     * The Phase 6 tabs really RENDER under /manage, and the write controls follow the
     * capability rather than the tab: a tab whose form is drawn for a read-only holder is
     * a UI that promises authority the service will refuse.
     */
    @Test
    @Order(7)
    void filesAndStatsTabsRenderAndTheWriteControlsFollowTheCapability() throws Exception {
        ensureManageGrant();
        String filesUrl = "/manage/instances/" + instanceAId + "/page/files";

        // 1. The Stats tab renders. The instance is not deployed, so the honest answer is
        //    the empty state -- a chart of nothing would be an invented reading.
        HttpResponse<String> stats = tenantGet("/manage/instances/" + instanceAId + "/page/stats");
        assertThat(stats.statusCode()).as("step 1: the stats tab renders").isEqualTo(200);
        assertThat(stats.body())
            .as("step 1: a stopped instance says so instead of drawing an empty chart")
            .contains("This instance is not running");

        // 2. The Files tab renders for a manage holder WITHOUT files.read, and says why it
        //    is empty rather than showing a blank browser that looks like an empty volume.
        HttpResponse<String> unread = tenantGet(filesUrl);
        assertThat(unread.statusCode()).as("step 2: the files tab renders").isEqualTo(200);
        assertThat(unread.body())
            .as("step 2: without files.read the refusal is SHOWN, never a blank listing")
            .contains("You are not allowed to perform this action on this instance");

        // 3. With files.read but NOT files.write, no mutating form is drawn at all.
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.FILES_READ, true);
        HttpResponse<String> readOnly = tenantGet(filesUrl);
        assertThat(readOnly.statusCode()).as("step 3: the read-only files tab renders").isEqualTo(200);
        assertThat(readOnly.body())
            .as("step 3: files.read draws no upload, mkdir or rename form")
            .doesNotContain("value=\"upload\"")
            .doesNotContain("value=\"mkdir\"")
            .doesNotContain("value=\"rename\"");

        // 4. The action endpoint answers a hand-rolled POST through the SERVICE gate: the
        //    absent form is a courtesy, the capability check is the authority.
        HttpResponse<String> forged = tenantPost("/instances/" + instanceAId + "/files/action",
            "action=mkdir&path=/data/forged&directory=/data");
        assertThat(forged.statusCode())
            .as("step 4: a hand-rolled POST is answered, not accepted silently")
            .isIn(200, 302, 303);
    }
    /**
     * The Phase 5b introduction gate: a template SCRIPT enters the system only through
     * the admin-gated import endpoint. The property is LAYERED and both layers are
     * probed: zenit-auth's {@code /admin} baseline ({@code auth.admin.access}) stops a
     * plain tenant, and the endpoint's OWN {@code hohenheim.admin.access} stops a
     * delegated auth-admin who passes the baseline. The operator import is the
     * positive anchor, and it still lands unapproved.
     */
    @Test
    @Order(8)
    void tenantCannotIntroduceACatalogScriptAndAnOperatorCan() throws Exception {
        long before = Models.get(InstanceTemplateModel.class).find()
            .where(InstanceTemplateModel.NAME.eq("Gotify")).count();

        // 1. The tenant's post never reaches the importer: zenit-auth's /admin
        //    baseline refuses it, and the catalog stays exactly as it was.
        HttpResponse<String> refused = tenantPost("/admin/instance-templates-import",
            "catalog_app=gotify");
        assertThat(refused.statusCode())
            .as("step 1: a tenant cannot reach the import endpoint")
            .isIn(302, 303, 403, 404);
        assertThat(Models.get(InstanceTemplateModel.class).find()
                .where(InstanceTemplateModel.NAME.eq("Gotify")).count())
            .as("step 1: and no template row was introduced")
            .isEqualTo(before);

        // 1b. A delegated AUTH admin (user management authority, auth.admin.access)
        //     passes the /admin baseline but must still hit the endpoint's own
        //     hohenheim.admin.access -- the layer this endpoint declares for itself.
        int authAdminId = tenant("auth-admin@surface.test", "Auth Admin");
        GrantService.createDirectGrant("user", authAdminId, "auth.admin.access", true);
        Session authSession = Zenit.getSessionStore().create();
        authSession.set(be.elevenways.zenit.auth.AuthKeys.USER_ID, (long) authAdminId);
        String authCsrf = ZenitAuth.randomToken();
        authSession.set(CsrfTokens.TOKEN, authCsrf);
        Zenit.getSessionStore().save(authSession);
        HttpClient authClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> authRefused = authClient.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/admin/instance-templates-import"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + authSession.id())
            .header("X-Csrf-Token", authCsrf)
            .POST(HttpRequest.BodyPublishers.ofString("catalog_app=gotify"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(authRefused.statusCode())
            .as("step 1b: an auth-admin without hohenheim.admin.access is refused"
                + " by the endpoint's own permission")
            .isIn(302, 303, 403, 404);
        assertThat(Models.get(InstanceTemplateModel.class).find()
                .where(InstanceTemplateModel.NAME.eq("Gotify")).count())
            .as("step 1b: and still no template row was introduced")
            .isEqualTo(before);

        // 2. Positive anchor: the OPERATOR's session imports the same app, and the
        //    imported row still lands unapproved (import is never the approval).
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> imported = client.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/admin/instance-templates-import"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString("catalog_app=gotify"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(imported.statusCode())
            .as("step 2: the operator's import redirects to the new template")
            .isIn(302, 303);
        Row introduced = Models.get(InstanceTemplateModel.class).find()
            .where(InstanceTemplateModel.NAME.eq("Gotify")).first();
        assertThat(introduced).as("step 2: the template row exists now").isNotNull();
        try {
            assertThat((Object) introduced.get(InstanceTemplateModel.APPROVED_AT))
                .as("step 2: and it landed UNAPPROVED").isNull();
        } finally {
            Models.get(InstanceTemplateModel.class)
                .delete(introduced.get(InstanceTemplateModel.ID));
        }
    }

    /**
     * COUNTERFACTUAL: the Console tab answers to {@code console}, not to the view scope
     * that decides whether the RECORD is visible.
     *
     * The tab does two things a view-only delegate must not reach: it fronts the live
     * terminal socket (whose handshake demands console) and it renders the RETAINED
     * console episodes straight out of InstanceLogModel. The second one shipped ungated,
     * so the page handed out the very output the socket refused -- which is why the
     * assertions below are on the stored TEXT and not on the tab's status alone.
     */
    @Test
    @Order(9)
    void theConsoleTabAnswersToConsoleAndNotToTheViewScope() throws Exception {
        int consoleInstanceId = instance(PREFIX + "console-scope");
        Model logs = Models.get(InstanceLogModel.class);
        String marker = "RETAINED-CONSOLE-" + PREFIX + "secret-line";
        Row log = logs.createEmptyRow();
        log.set(InstanceLogModel.INSTANCE_ID, consoleInstanceId);
        log.set(InstanceLogModel.HANDLE, PREFIX + "console-handle");
        log.set(InstanceLogModel.LOG_TEXT, marker);
        log.set(InstanceLogModel.LINE_COUNT, 1);
        log.set(InstanceLogModel.SAVED_AT, Instant.now());
        logs.save(log);
        Integer logId = log.get(InstanceLogModel.ID);
        String tabUrl = "/manage/instances/" + consoleInstanceId + "/page/console";

        try {
            // 1. A VIEW-only delegate. The record itself is genuinely visible, so every
            //    refusal below is about the ACT and not about the record being hidden.
            RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, consoleInstanceId,
                HohenheimAccess.VIEW, true);
            assertThat(tenantGet("/manage/instances/" + consoleInstanceId).statusCode())
                .as("step 1: the view delegate really can open the record")
                .isEqualTo(200);

            // 2. THE ATTACK: the tab itself. zenit-cms 404s a slug the page does not
            //    offer, so hiding it IS the gate on the route.
            HttpResponse<String> tab = tenantGet(tabUrl);
            assertThat(tab.statusCode())
                .as("step 2: a view-only delegate has no console tab").isEqualTo(404);
            assertThat(tab.body())
                .as("step 2: and no retained console output leaked with the refusal")
                .doesNotContain(marker);

            // 3. And the deep link that selects a stored episode by id -- the actual leak
            //    shape, since the listing alone would only name the episodes.
            HttpResponse<String> deepLink = tenantGet(tabUrl + "?log=" + logId);
            assertThat(deepLink.statusCode())
                .as("step 3: selecting a stored log by id is refused too").isEqualTo(404);
            assertThat(deepLink.body())
                .as("step 3: the workload's captured output never reaches a view delegate")
                .doesNotContain(marker);

            // 4. POSITIVE ANCHOR: the console holder gets the tab AND the stored text.
            //    Without this the test would pass on a tab that is simply broken.
            RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, consoleInstanceId,
                HohenheimAccess.CONSOLE, true);
            HttpResponse<String> allowed = tenantGet(tabUrl + "?log=" + logId);
            assertThat(allowed.statusCode())
                .as("step 4: the console delegate opens the tab").isEqualTo(200);
            assertThat(allowed.body())
                .as("step 4: and reads the retained episode it is entitled to")
                .contains(marker);
        } finally {
            logs.find().where(InstanceLogModel.INSTANCE_ID.eq(consoleInstanceId)).delete();
        }
    }

    /** An admitted, container-accepting host an operator would have enrolled. */
    private static int admittedHost() {
        Model servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + "host");
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        servers.save(row);
        // Placement will not CHOOSE a host whose memory nobody measured, so an admitted
        // host needs the reading a real admit always has (requireAdmittable demands a
        // passing preflight, and both batteries store mem_total).
        HostPreflight.store(PREFIX + "host", new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }
}
