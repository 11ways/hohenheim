package be.elevenways.hohenheim.test.project;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Projects as OWNERS: membership is authorization (never decoration), the ownership
 * derivation stays ONE (sameOwner, quota bucket and the planted grant all follow the
 * project subject), quota binds PER PROJECT, and environments group without ever
 * disagreeing with the grants.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProjectOwnershipTest extends HohenheimTestBase {

    private static final String PREFIX = "proj-own-";

    private static Integer memberAId;
    private static Integer memberBId;
    private static String sessionA;
    private static String csrfA;
    private static UserPrincipal principalA;

    private static Integer projectOneId;
    private static Integer projectTwoId;
    private static Row projectOne;
    private static Row projectTwo;

    private static Integer alphaId;
    private static Integer betaId;
    private static Integer gammaId;
    private static Integer omegaId;
    private static Integer environmentId;
    private static Integer templateId;
    private static Integer admittedHostId;

    @BeforeAll
    static void seed() {
        memberAId = user("member-a@project.test", "Member A");
        memberBId = user("member-b@project.test", "Member B");
        principalA = new UserPrincipal(memberAId, "Member A");

        Session session = Zenit.getSessionStore().create();
        session.set(be.elevenways.zenit.auth.AuthKeys.USER_ID, memberAId.longValue());
        csrfA = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrfA);
        Zenit.getSessionStore().save(session);
        sessionA = session.token().secret();

        projectOneId = project(PREFIX + "one");
        projectTwoId = project(PREFIX + "two");
        projectOne = Models.get(ProjectModel.class).findById(projectOneId);
        projectTwo = Models.get(ProjectModel.class).findById(projectTwoId);
        Projects.addMember(projectOne, memberAId);
        Projects.addMember(projectTwo, memberBId);

        alphaId = instance(PREFIX + "alpha");
        betaId = instance(PREFIX + "beta");
        gammaId = instance(PREFIX + "gamma");
        omegaId = instance(PREFIX + "omega");   // stays operator-owned
        Projects.adoptRecord(projectOne, InstanceModel.MODEL_ID, alphaId);
        Projects.adoptRecord(projectOne, InstanceModel.MODEL_ID, betaId);
        Projects.adoptRecord(projectTwo, InstanceModel.MODEL_ID, gammaId);

        Row environment = Models.get(EnvironmentModel.class).createEmptyRow();
        environment.set(EnvironmentModel.PROJECT_ID, projectOneId);
        environment.set(EnvironmentModel.NAME, PREFIX + "production");
        Models.get(EnvironmentModel.class).save(environment);
        environmentId = environment.get(EnvironmentModel.ID);

        templateId = template(PREFIX + "template");
    }

    @AfterAll
    static void cleanUp() {
        Model variables = Models.get(InstanceVariableModel.class);
        for (Row row : variables.find()
                .where(InstanceVariableModel.KEY.startsWith("PROJ_OWN_")).all()) {
            variables.delete(row.get(InstanceVariableModel.ID));
        }
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
        Model environments = Models.get(EnvironmentModel.class);
        for (Row row : environments.find()
                .where(EnvironmentModel.NAME.startsWith(PREFIX)).all()) {
            environments.delete(row.get(EnvironmentModel.ID));
        }
        Model quotas = Models.get(InstanceQuotaModel.class);
        for (Row project : new Row[] {projectOne, projectTwo}) {
            if (project == null || project.get(ProjectModel.GROUP_ID) == null) {
                continue;
            }
            String pack = HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(project));
            for (Row row : quotas.find().where(InstanceQuotaModel.SUBJECTS.eq(pack)).all()) {
                quotas.delete(row.get(InstanceQuotaModel.ID));
            }
        }
        Model projects = Models.get(ProjectModel.class);
        for (Row row : projects.find().where(ProjectModel.NAME.startsWith(PREFIX)).all()) {
            projects.delete(row.get(ProjectModel.ID));
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find()
                .where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            templates.delete(row.get(InstanceTemplateModel.ID));
        }
        if (admittedHostId != null) {
            Models.get(ServerModel.class).delete(admittedHostId);
        }
    }

    // -- fixtures -------------------------------------------------------------

    private static int user(String email, String name) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, name);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static int project(String name) {
        Row row = Models.get(ProjectModel.class).createEmptyRow();
        row.set(ProjectModel.NAME, name);
        Models.get(ProjectModel.class).save(row);
        return row.get(ProjectModel.ID);
    }

    private static int instance(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static int template(String name) {
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest", "command", "sleep 300")));
        row.set(InstanceTemplateModel.APPROVED_AT, Instant.now());
        row.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        Models.get(InstanceTemplateModel.class).save(row);
        return row.get(InstanceTemplateModel.ID);
    }

    private static int admittedHost() {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + "host");
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.PREFLIGHT_OK, true);
        Models.get(ServerModel.class).save(row);
        // Placement will not CHOOSE a host whose memory nobody measured, so an admitted
        // host needs the reading a real admit always has (requireAdmittable demands a
        // passing preflight, and both batteries store mem_total). Through the store
        // funnel, never a hand-written capabilities shape.
        HostPreflight.store(PREFIX + "host", new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return row.get(ServerModel.ID);
    }

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> memberGet(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionA)
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> memberPost(String path, String body) throws Exception {
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

    /**
     * COUNTERFACTUAL 1 -- membership is authorization, not decoration: a member of
     * project ONE sees and acts on project ONE's records through the GROUP grant
     * alone, and project TWO's records answer as if they did not exist, asserted on
     * CONTENT, not status codes.
     */
    @Test
    @Order(1)
    void membershipIsAuthorizationAcrossListDetailAndAction() throws Exception {
        // 1. Ownership derives to exactly the project subject -- no user grant exists.
        assertThat(HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, alphaId))
            .as("step 1: the record's one manage subject is the project group")
            .containsExactly(Projects.subjectOf(projectOne));

        // 2. The membership walk opens /manage and the list carries ONLY project
        //    one's names: the leak would be a NAME in the body.
        HttpResponse<String> list = memberGet("/manage/instances");
        assertThat(list.statusCode()).as("step 2: the scoped list renders").isEqualTo(200);
        assertThat(list.body())
            .as("step 2: project one's instances are listed for its member")
            .contains(PREFIX + "alpha").contains(PREFIX + "beta");
        assertThat(list.body())
            .as("step 2: project two's instance NEVER appears in the member's list")
            .doesNotContain(PREFIX + "gamma");
        assertThat(list.body())
            .as("step 2: nor does the operator's projectless instance")
            .doesNotContain(PREFIX + "omega");

        // 3. A foreign project's record is indistinguishable from a missing one.
        int absentId = 900_000_100;
        assertThat(Models.get(InstanceModel.class).findById(absentId)).isNull();
        HttpResponse<String> foreign = memberGet("/manage/instances/" + gammaId);
        HttpResponse<String> absent = memberGet("/manage/instances/" + absentId);
        assertThat(foreign.statusCode())
            .as("step 3: project two's record reads as MISSING for a non-member")
            .isEqualTo(404);
        assertThat(foreign.body())
            .as("step 3: same body as a nonexistent id -- no existence oracle")
            .isEqualTo(absent.body());

        // 4. The ACTION follows the same walk: on the member's project the capability
        //    gate passes (the refusal moves to host admission); on the foreign project
        //    the capability gate itself refuses. The distinct keys are the evidence.
        Throwable own = catchThrowable(() ->
            TenantConduits.as(principalA, () -> new InstanceService().deploy(alphaId)));
        assertThat(violationKeys(own))
            .as("step 4: the member passes the capability gate via the GROUP grant")
            .contains("host_not_admitted")
            .doesNotContain("instance_not_permitted");
        Throwable foreignAct = catchThrowable(() ->
            TenantConduits.as(principalA, () -> new InstanceService().deploy(gammaId)));
        assertThat(violationKeys(foreignAct))
            .as("step 4: the SAME call on project two's record is refused by the gate")
            .contains("instance_not_permitted");
    }

    /**
     * COUNTERFACTUAL 2 (structural half) -- one derivation, not two: sameOwner, the
     * quota bucket charged by a real create and the planted grant all answer from the
     * project subject, through one funnel.
     */
    @Test
    @Order(2)
    void createIntoAProjectChargesPlacesAndGrantsFromOneDerivation() throws Exception {
        // 1. sameOwner agrees within and disagrees across projects.
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, alphaId, betaId))
            .as("step 1: two records of one project are one owner").isTrue();
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, alphaId, gammaId))
            .as("step 1: records of different projects are different owners").isFalse();
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, alphaId, omegaId))
            .as("step 1: a project record and an operator record differ too").isFalse();

        // 2. A member creates THROUGH the one funnel, into its project.
        GrantService.createDirectGrant("user", memberAId,
            HohenheimAccess.INSTANCES_CREATE.value(), true);
        admittedHostId = admittedHost();
        HttpResponse<String> created = memberPost("/instances/from-template",
            "template_id=" + templateId + "&name=" + PREFIX + "created"
                + "&project_id=" + projectOneId);
        assertThat(created.statusCode()).as("step 2: the project create lands").isIn(302, 303);
        Row instance = Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.eq(PREFIX + "created")).first();
        assertThat(instance).as("step 2: the instance exists").isNotNull();

        // 3. The planted grant names the PROJECT, not the creating user: ownership
        //    went to the project in the same move that charged its bucket.
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID,
            instance.get(InstanceModel.ID));
        assertThat(subjects)
            .as("step 3: the manage grant is held by the project group alone")
            .containsExactly(Projects.subjectOf(projectOne));

        // 4. The charged bucket is the PROJECT's -- the same packing sameOwner
        //    compares and the placement chooser labels hosts with.
        String projectBucket = InstanceQuota.bucketKeyOf(
            HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(projectOne)));
        assertThat((String) instance.get(InstanceModel.QUOTA_BUCKET))
            .as("step 4: the create charged the project's bucket")
            .isEqualTo(projectBucket);
        assertThat((String) instance.get(InstanceModel.QUOTA_BUCKET))
            .as("step 4: and not the member's personal bucket")
            .isNotEqualTo(InstanceQuota.bucketKeyOf(
                HohenheimAccess.packSubjects(Set.of("user:" + memberAId))));

        // 5. And the creator still REACHES it -- through membership, not a direct grant.
        assertThat(memberGet("/manage/instances").body())
            .as("step 5: the created instance appears in the member's scoped list")
            .contains(PREFIX + "created");
    }

    /**
     * COUNTERFACTUAL 3 -- quota is enforced at the PROJECT level: exhausting project
     * one refuses its next create while project two is untouched.
     */
    @Test
    @Order(3)
    void projectQuotaBindsItsOwnProjectAndNoOther() throws Exception {
        String packOne = HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(projectOne));
        Row cap = Models.get(InstanceQuotaModel.class).createEmptyRow();
        cap.set(InstanceQuotaModel.SUBJECTS, packOne);
        cap.set(InstanceQuotaModel.MAX_INSTANCES, (int) InstanceQuota.usedBy(packOne));
        Models.get(InstanceQuotaModel.class).save(cap);

        // 1. Project one is full: the next create into it is refused BY NAME and
        //    persists nothing.
        HttpResponse<String> refused = memberPost("/instances/from-template",
            "template_id=" + templateId + "&name=" + PREFIX + "over-cap"
                + "&project_id=" + projectOneId);
        assertThat(refused.body())
            .as("step 1: the project cap refuses the create, named")
            .contains("Instance quota reached");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "over-cap")).count())
            .as("step 1: nothing landed").isZero();

        // 2. A DIFFERENT project is unaffected by project one's exhaustion: the same
        //    member (joining project two) creates there immediately.
        Projects.addMember(projectTwo, memberAId);
        HttpResponse<String> other = memberPost("/instances/from-template",
            "template_id=" + templateId + "&name=" + PREFIX + "in-two"
                + "&project_id=" + projectTwoId);
        assertThat(other.statusCode())
            .as("step 2: project two accepts while project one is full").isIn(302, 303);
        Row landed = Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.eq(PREFIX + "in-two")).first();
        assertThat(landed).as("step 2: and the record exists").isNotNull();
        assertThat((String) landed.get(InstanceModel.QUOTA_BUCKET))
            .as("step 2: charged to project TWO's bucket")
            .isEqualTo(InstanceQuota.bucketKeyOf(
                HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(projectTwo))));

        Models.get(InstanceQuotaModel.class).delete(cap.get(InstanceQuotaModel.ID));
    }

    /** Environments group ONLY what their project owns, and their variables merge under. */
    @Test
    @Order(4)
    void environmentsGroupOwnedRecordsAndLayerVariables() {
        // 1. Attaching a project-one instance to a project-one environment is fine.
        Row alpha = Models.get(InstanceModel.class).findById(alphaId);
        alpha.set(InstanceModel.ENVIRONMENT_ID, environmentId);
        Models.get(InstanceModel.class).save(alpha);
        assertThat((Integer) Models.get(InstanceModel.class).findById(alphaId)
                .get(InstanceModel.ENVIRONMENT_ID))
            .as("step 1: the owned instance joined the environment")
            .isEqualTo(environmentId);

        // 2. An OPERATOR-owned instance cannot join a project's environment: grouping
        //    would disagree with the grants, which is the failure the guard refuses.
        //    The refusal fires BEFORE the quota hook (beforeValidate), so no
        //    reservation is spent by the aborted write -- the usage must not move.
        long operatorUsedBefore = InstanceQuota.usedBy("");
        Throwable mismatch = catchThrowable(() -> {
            Row omega = Models.get(InstanceModel.class).findById(omegaId);
            omega.set(InstanceModel.ENVIRONMENT_ID, environmentId);
            Models.get(InstanceModel.class).save(omega);
        });
        assertThat(violationKeys(mismatch))
            .as("step 2: the grouping guard refuses by name")
            .contains("environment_project_mismatch");

        // And the CREATE lane: a refused environment must fire BEFORE the quota
        // hook reserves (beforeValidate vs beforeWrite), or the aborted create
        // leaks a spent slot. The operator bucket must not move.
        Throwable refusedCreate = catchThrowable(() -> {
            Row stray = Models.get(InstanceModel.class).createEmptyRow();
            stray.set(InstanceModel.NAME, PREFIX + "stray");
            stray.set(InstanceModel.KIND, "hohenheim:docker_container");
            stray.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
                Map.of("image", "alpine", "tag", "latest")));
            stray.set(InstanceModel.ENVIRONMENT_ID, environmentId);
            Models.get(InstanceModel.class).save(stray);
        });
        assertThat(violationKeys(refusedCreate))
            .as("step 2: an operator create into a project environment is refused")
            .contains("environment_project_mismatch");
        assertThat(InstanceQuota.usedBy(""))
            .as("step 2: the refused create spent NO reservation")
            .isEqualTo(operatorUsedBefore);
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "stray")).count())
            .as("step 2: and persisted nothing").isZero();

        // 3. A variable row must have exactly ONE owner.
        Throwable both = catchThrowable(() -> {
            Row row = Models.get(InstanceVariableModel.class).createEmptyRow();
            row.set(InstanceVariableModel.INSTANCE_ID, alphaId);
            row.set(InstanceVariableModel.ENVIRONMENT_ID, environmentId);
            row.set(InstanceVariableModel.KEY, "PROJ_OWN_BROKEN");
            row.set(InstanceVariableModel.PLAIN_VALUE, "x");
            Models.get(InstanceVariableModel.class).save(row);
        });
        assertThat(violationKeys(both))
            .as("step 3: two owners on one variable row are refused")
            .contains("variable_one_owner");

        // 4. Environment values are the BASELINE and the instance's own row wins.
        variable(null, environmentId, "PROJ_OWN_SHARED", "from-env");
        variable(null, environmentId, "PROJ_OWN_ONLY", "env-only");
        variable(alphaId, null, "PROJ_OWN_SHARED", "from-instance");
        Map<String, String> values = new InstanceVariables().valuesFor(alphaId);
        assertThat(values.get("PROJ_OWN_SHARED"))
            .as("step 4: the instance value overrides the environment baseline")
            .isEqualTo("from-instance");
        assertThat(values.get("PROJ_OWN_ONLY"))
            .as("step 4: an environment-only value reaches the instance")
            .isEqualTo("env-only");

        // 5. A referenced environment refuses deletion by name.
        Throwable inUse = catchThrowable(() ->
            Models.get(EnvironmentModel.class).delete(environmentId));
        assertThat(violationKeys(inUse))
            .as("step 5: an in-use environment cannot be deleted")
            .contains("environment_in_use");

        // 6. Detach and empty it; deletion then passes (exercised in cleanup order).
        Row alphaAgain = Models.get(InstanceModel.class).findById(alphaId);
        alphaAgain.set(InstanceModel.ENVIRONMENT_ID, (Integer) null);
        Models.get(InstanceModel.class).save(alphaAgain);
    }

    /** A project that still owns records refuses deletion; an emptied one cleans up. */
    @Test
    @Order(5)
    void projectDeletionIsGuardedAndTearsDownItsAuthFootprint() {
        // 1. Project two owns gamma (and the test-3 create): deletion is refused.
        Throwable refused = catchThrowable(() ->
            Models.get(ProjectModel.class).delete(projectTwoId));
        assertThat(violationKeys(refused))
            .as("step 1: a project still owning records refuses deletion")
            .contains("project_not_empty");

        // 2. Empty a THROWAWAY project and delete it: its auth group disappears.
        int throwawayId = project(PREFIX + "throwaway");
        Row throwaway = Models.get(ProjectModel.class).findById(throwawayId);
        Integer groupId = throwaway.get(ProjectModel.GROUP_ID);
        assertThat(groupId).as("step 2: the write hook created the backing group").isNotNull();
        assertThat(AuthModels.permissionGroups().findById(groupId))
            .as("step 2: and the group row exists").isNotNull();
        Models.get(ProjectModel.class).delete(throwawayId);
        assertThat(AuthModels.permissionGroups().findById(groupId))
            .as("step 2: deleting the project deleted its group explicitly")
            .isNull();
    }

    private static void variable(Integer instanceId, Integer envId, String key, String value) {
        Row row = Models.get(InstanceVariableModel.class).createEmptyRow();
        if (instanceId != null) {
            row.set(InstanceVariableModel.INSTANCE_ID, instanceId);
        }
        if (envId != null) {
            row.set(InstanceVariableModel.ENVIRONMENT_ID, envId);
        }
        row.set(InstanceVariableModel.KEY, key);
        row.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_PLAIN);
        row.set(InstanceVariableModel.PLAIN_VALUE, value);
        Models.get(InstanceVariableModel.class).save(row);
    }
}
