package be.elevenways.hohenheim.test.project;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.PlacementRefusal;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.cms.server.panel.PartsWrites;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.microsoft.playwright.Locator;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Projects as OWNERS: membership is authorization (never decoration), the ownership
 * derivation stays ONE (sameOwner, quota bucket and the planted grant all follow the
 * project subject), quota binds PER PROJECT, and environments group without ever
 * disagreeing with the grants.
 *
 * AIDEV-NOTE: membership, the one-derivation create and the project quota are ONE journey
 * on purpose: the create needs the admitted host and the create grant, and admitting that
 * host (or joining project two) would change what the membership phase proves. They used
 * to be three @Order'ed tests coupled through that state; the other tests stand alone.
 */
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
    static void seed() throws Exception {
        // The roles and instance first pages belong to this journey's project inventory.
        freshSeededDatabase();
        memberAId = ApiSupport.user("member-a@project.test", "Member A");
        memberBId = ApiSupport.user("member-b@project.test", "Member B");
        principalA = new UserPrincipal(memberAId, "Member A");

        TestSession member = sessionFor(memberAId);
        sessionA = member.token();
        csrfA = member.csrf();

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
        for (Row row : instances.find().withTrashed().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            HardDeletes.byId(instances, row.get(InstanceModel.ID));
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
        row.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
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
        HostFixtures.acknowledgePosture(row);
        // Placement will not CHOOSE a host whose memory nobody measured, so an admitted
        // host needs the reading a real admit always has (requireAdmittable demands a
        // passing preflight, and both batteries store mem_total). Through the store
        // funnel, never a hand-written capabilities shape.
        HostPreflight.store(PREFIX + "host", new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Now.instant(), null));
        return row.get(ServerModel.ID);
    }

    private HttpResponse<String> memberGet(String path) throws Exception {
        return httpGet(path, sessionA);
    }

    private HttpResponse<String> memberPost(String path, String body) throws Exception {
        return httpPostForm(path, body, sessionA, csrfA);
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
     * Membership is authorization, a create into the project charges, places and grants
     * from one derivation, and the project's quota binds it and no other project.
     */
    @Test
    void aProjectMembershipAuthorizesCreatesIntoAndIsCappedByItsProject() throws Exception {
        // 1. Membership is authorization across list, detail and action.
        membershipIsAuthorizationAcrossListDetailAndAction();
        // 2. A member's create into the project rides the one ownership derivation.
        createIntoAProjectChargesPlacesAndGrantsFromOneDerivation();
        // 3. The project's quota binds that project and no other.
        projectQuotaBindsItsOwnProjectAndNoOther();
    }

    /**
     * COUNTERFACTUAL 1 -- membership is authorization, not decoration: a member of
     * project ONE sees and acts on project ONE's records through the GROUP grant
     * alone, and project TWO's records answer as if they did not exist, asserted on
     * CONTENT, not status codes.
     */
    private void membershipIsAuthorizationAcrossListDetailAndAction() throws Exception {
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
        assertThat(Models.get(InstanceModel.class).findById(absentId))
            .as("step 3: fixture: the absent id really is absent").isNull();
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
        // instance_placement_blocked, not host_not_admitted: the placement gate withholds
        // the host's NAME from a tenant-originated call (HostAdmission's substitution).
        // What this step proves is unchanged -- the refusal moved PAST the capability gate.
        assertThat(violationKeys(own))
            .as("step 4: the member passes the capability gate via the GROUP grant")
            .contains("instance_placement_blocked")
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
    private void createIntoAProjectChargesPlacesAndGrantsFromOneDerivation() throws Exception {
        // 1. sameOwner agrees within and disagrees across projects.
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, alphaId, betaId))
            .as("step 1: two records of one project are one owner").isTrue();
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, alphaId, gammaId))
            .as("step 1: records of different projects are different owners").isFalse();
        assertThat(HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, alphaId, omegaId))
            .as("step 1: a project record and an operator record differ too").isFalse();

        // 2. A member creates THROUGH the one funnel, into its project.
        GrantService.createDirectGrant(GrantSubjectType.USER, memberAId,
            HohenheimAccess.INSTANCES_CREATE.value(), true);
        admittedHostId = admittedHost();
        HttpResponse<String> created = memberPost(ApiSupport.fromTemplateTarget(HohenheimSlugs.MANAGE, templateId),
            "name=" + PREFIX + "created" + "&projectId=" + projectOneId + "&" + ApiSupport.fromTemplateTransport());
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
        String projectBucket = OwnerBudget.INSTANCES.bucketOf(
            HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(projectOne)));
        assertThat((String) instance.get(InstanceModel.QUOTA_BUCKET))
            .as("step 4: the create charged the project's bucket")
            .isEqualTo(projectBucket);
        assertThat((String) instance.get(InstanceModel.QUOTA_BUCKET))
            .as("step 4: and not the member's personal bucket")
            .isNotEqualTo(OwnerBudget.INSTANCES.bucketOf(
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
    private void projectQuotaBindsItsOwnProjectAndNoOther() throws Exception {
        String packOne = HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(projectOne));
        Row cap = Models.get(InstanceQuotaModel.class).createEmptyRow();
        cap.set(InstanceQuotaModel.SUBJECTS, packOne);
        cap.set(InstanceQuotaModel.MAX_INSTANCES, (int) OwnerBudget.INSTANCES.usedBy(packOne));
        Models.get(InstanceQuotaModel.class).save(cap);

        // 1. Project one is full: the next create into it is refused BY NAME and
        //    persists nothing.
        HttpResponse<String> refused = memberPost(ApiSupport.fromTemplateTarget(HohenheimSlugs.MANAGE, templateId),
            "name=" + PREFIX + "over-cap" + "&projectId=" + projectOneId + "&" + ApiSupport.fromTemplateTransport());
        assertThat(refused.body())
            .as("step 1: the project cap refuses the create, named")
            .contains("Instance quota reached");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "over-cap")).count())
            .as("step 1: nothing landed").isZero();

        // 2. A DIFFERENT project is unaffected by project one's exhaustion: the same
        //    member (joining project two) creates there immediately.
        Projects.addMember(projectTwo, memberAId);
        HttpResponse<String> other = memberPost(ApiSupport.fromTemplateTarget(HohenheimSlugs.MANAGE, templateId),
            "name=" + PREFIX + "in-two" + "&projectId=" + projectTwoId + "&" + ApiSupport.fromTemplateTransport());
        assertThat(other.statusCode())
            .as("step 2: project two accepts while project one is full").isIn(302, 303);
        Row landed = Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.eq(PREFIX + "in-two")).first();
        assertThat(landed).as("step 2: and the record exists").isNotNull();
        assertThat((String) landed.get(InstanceModel.QUOTA_BUCKET))
            .as("step 2: charged to project TWO's bucket")
            .isEqualTo(OwnerBudget.INSTANCES.bucketOf(
                HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(projectTwo))));

        Models.get(InstanceQuotaModel.class).delete(cap.get(InstanceQuotaModel.ID));
    }

    /**
     * The environment pick offers exactly what the grouping guard accepts: the environments of the project that owns
     * the record, never another owner's, and creates a new one INTO that project, never for a record without one.
     */
    @Test
    void theEnvironmentPickOffersOnlyWhatTheSaveAccepts() {
        String environmentSelect = "pl-select[name='environment_id']";
        String popup = "he-bottom .pl-select-popup[data-open]";
        String production = popup + " div[role='option'][data-value='" + environmentId + "']";

        // 1. A project-one record is offered project one's environment.
        navigateToApp("/admin/instances/" + betaId);
        waitForHydration();
        page.click(environmentSelect + " .pl-select-field");
        page.waitForSelector(production);

        // 2. An operator-owned record is not: the save would refuse it, so the pick never offers it, and its empty
        //    list says why in words instead of a bare "No results".
        navigateToApp("/admin/instances/" + omegaId);
        waitForHydration();
        if (page.locator(environmentSelect + "[disabled]").count() == 0) {
            page.click(environmentSelect + " .pl-select-field");
            page.waitForSelector(popup);
            page.waitForCondition(() -> page.locator(popup + " div[role='option']").count() > 0
                || page.locator(popup + " .pl-select-empty").count() > 0);
            assertThat(page.locator(production).count())
                .as("step 2: another project's environment is not offered to an operator-owned record").isZero();
            assertThat(page.locator(popup + " .pl-select-empty").textContent())
                .as("step 2: the empty pick says the record belongs to no project")
                .contains("belongs to no project");
            assertThat(page.locator(popup + " .pl-select-create").count())
                .as("step 2: and offers no create: an environment made for it could never be chosen").isZero();
        }

        // 3. A record of a project without environments reads that project's name and offers to create one.
        navigateToApp("/admin/instances/" + gammaId);
        waitForHydration();
        page.click(environmentSelect + " .pl-select-field");
        page.waitForSelector(popup + " .pl-select-empty");
        assertThat(page.locator(popup + " .pl-select-empty").textContent())
            .as("step 3: the empty pick names the project and says to create one")
            .contains(PREFIX + "two has no environments yet").contains("Create one here");

        // 4. The inline create makes the environment IN the record's project, and the pick adopts it.
        String created = PREFIX + "inline-staging";
        page.click(popup + " .pl-select-create");
        String createForm = "[data-zf-create-form='environment_id']";
        page.waitForSelector(createForm + " input[name='name']");
        page.fill(createForm + " input[name='name']", created);
        page.click("[data-zf-create-submit='environment_id']");
        page.waitForCondition(() -> Models.get(EnvironmentModel.class).find()
            .where(EnvironmentModel.NAME.eq(created)).first() != null);
        Row staging = Models.get(EnvironmentModel.class).find().where(EnvironmentModel.NAME.eq(created)).first();
        try {
            assertThat((Integer) staging.get(EnvironmentModel.PROJECT_ID))
                .as("step 4: the environment was created in the record's own project").isEqualTo(projectTwoId);
            page.waitForCondition(() -> page.locator(environmentSelect + " .pl-select-field").textContent()
                .contains(created));
        } finally {
            Models.get(EnvironmentModel.class).delete(staging.get(EnvironmentModel.ID));
        }
    }

    /**
     * The operator's create form makes an app INTO a chosen project: the project owns it from the first write (its
     * group holds manage, its bucket is charged), the environment pick narrows to that project's, another project's
     * environment is refused, and a principal who is no member of the project cannot create into it.
     */
    @Test
    void anOperatorCreatesAnAppIntoTheProjectItChooses() throws Exception {
        String projectSelect = "pl-select[name='project_id']";
        String environmentSelect = "pl-select[name='environment_id']";
        String popup = "he-bottom .pl-select-popup[data-open]";
        Row foreign = Models.get(EnvironmentModel.class).createEmptyRow();
        foreign.set(EnvironmentModel.PROJECT_ID, projectTwoId);
        foreign.set(EnvironmentModel.NAME, PREFIX + "two-staging");
        Models.get(EnvironmentModel.class).save(foreign);
        Integer foreignId = foreign.get(EnvironmentModel.ID);
        int hostId = HostFixtures.admittedDockerHost(PREFIX + "admin-host", 16384);
        String created = PREFIX + "admin-created";
        Model instances = Models.get(InstanceModel.class);
        try {
            // 1. The create form offers the project an app is created into; a stored record's form does not (its
            //    Access tab moves it).
            navigateToApp("/admin/instances/" + omegaId);
            waitForHydration();
            assertThat(page.locator(projectSelect).count())
                .as("step 1: an existing record's form has no project pick").isZero();
            navigateToApp("/admin/instances/new");
            waitForHydration();
            assertThat(page.locator(projectSelect).count()).as("step 1: the create form has one").isEqualTo(1);

            // 2. Choosing project one narrows the environment pick to project one's environments.
            page.click(projectSelect + " .pl-select-field");
            String one = popup + " div[role='option'][data-value='" + projectOneId + "']";
            page.waitForSelector(one);
            page.click(one);
            waitForReactiveIdle();
            page.click(environmentSelect + " .pl-select-field");
            page.waitForSelector(popup + " div[role='option'][data-value='" + environmentId + "']");
            assertThat(page.locator(popup + " div[role='option'][data-value='" + foreignId + "']").count())
                .as("step 2: project two's environment is not offered beside project one").isZero();

            // 3. The submit lands INTO project one: its group alone holds manage, and its bucket was charged.
            HttpResponse<String> landed = httpPostForm("/admin/instances/new", "name=" + created
                + "&kind=hohenheim%3Adocker_container&server_id=" + hostId + "&project_id=" + projectOneId
                + "&environment_id=" + environmentId, sessionToken, csrfToken);
            assertThat(landed.statusCode()).as("step 3: the create redirects").isIn(302, 303);
            Row instance = instances.find().where(InstanceModel.NAME.eq(created)).first();
            assertThat(instance).as("step 3: the instance exists").isNotNull();
            assertThat(HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, instance.get(InstanceModel.ID)))
                .as("step 3: the project group alone manages it").containsExactly(Projects.subjectOf(projectOne));
            assertThat((String) instance.get(InstanceModel.QUOTA_BUCKET))
                .as("step 3: the project's bucket was charged").isEqualTo(OwnerBudget.INSTANCES.bucketOf(
                    HohenheimAccess.packSubjects(Projects.ownerSubjectsOf(projectOne))));
            assertThat((Integer) instance.get(InstanceModel.ENVIRONMENT_ID))
                .as("step 3: grouped in the chosen environment").isEqualTo(environmentId);

            // 4. Another project's environment beside project one is refused, on the form and on the persist lane.
            String mismatched = PREFIX + "admin-mismatched";
            HttpResponse<String> refused = httpPostForm("/admin/instances/new", "name=" + mismatched
                + "&kind=hohenheim%3Adocker_container&server_id=" + hostId + "&project_id=" + projectOneId
                + "&environment_id=" + foreignId, sessionToken, csrfToken);
            assertThat(refused.statusCode()).as("step 4: the form refuses, re-rendered").isNotIn(302, 303);
            Throwable direct = catchThrowable(() -> PartsWrites.persistRow(
                PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES),
                Map.<String, Object>of("name", mismatched, "kind", "hohenheim:docker_container", "server_id", hostId,
                    "project_id", projectOneId, "environment_id", foreignId),
                TenantConduits.operator()));
            assertThat(violationKeys(direct)).as("step 4: the persist lane refuses with the one owner refusal")
                .contains("project_member_required");
            assertThat(instances.find().where(InstanceModel.NAME.eq(mismatched)).count())
                .as("step 4: nothing landed").isZero();

            // 5. A principal who is no member of project one cannot create into it.
            String intruded = PREFIX + "admin-intruded";
            Throwable intruder = catchThrowable(() -> PartsWrites.persistRow(
                PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES),
                Map.<String, Object>of("name", intruded, "kind", "hohenheim:docker_container", "server_id", hostId,
                    "project_id", projectOneId),
                AccessContext.of(TenantConduits.stubFor(new UserPrincipal(memberBId, "Member B")))));
            assertThat(violationKeys(intruder)).as("step 5: a non-member is refused the project")
                .contains("project_member_required");
            assertThat(((Violations) intruder).all()).as("step 5: on the project field")
                .anyMatch(violation -> violation.fieldName().equals("project_id"));
            assertThat(instances.find().where(InstanceModel.NAME.eq(intruded)).count())
                .as("step 5: nothing landed").isZero();
        } finally {
            for (Row row : instances.find().withTrashed().where(InstanceModel.NAME.startsWith(PREFIX + "admin-"))
                    .all()) {
                HardDeletes.byId(instances, row.get(InstanceModel.ID));
            }
            Models.get(EnvironmentModel.class).delete(foreignId);
            Models.get(ServerModel.class).delete(hostId);
        }
    }

    /**
     * A host pick judges a host for the owner the save charges: a host dedicated to project one is refused to the
     * operator's own create and to project two, and offered once project one, or one of its environments, is chosen;
     * the template create's host pick judges the same way, and each submit agrees with what the pick showed.
     */
    @Test
    void aDedicatedHostIsJudgedForTheOwnerTheCreateCharges() throws Exception {
        String hostSelect = "pl-select[name='server_id']";
        String projectSelect = "pl-select[name='project_id']";
        String environmentSelect = "pl-select[name='environment_id']";
        String popup = "he-bottom .pl-select-popup[data-open]";
        String hostName = PREFIX + "dedicated";
        int hostId = HostFixtures.admittedDockerHost(hostName, 16384);
        Model servers = Models.get(ServerModel.class);
        Model instances = Models.get(InstanceModel.class);
        Row dedicated = servers.findById(hostId);
        dedicated.set(ServerModel.POSTURE, ServerModel.POSTURE_DEDICATED);
        servers.save(dedicated);
        HostFixtures.acknowledgePosture(servers.findById(hostId));
        String bucketOne = OwnerBudget.INSTANCES.bucketOf(HohenheimAccess.packSubjects(
            Projects.ownerSubjectsOf(projectOne)));
        int occupant = instance(PREFIX + "dedicated-occupant");
        instances.find().where(InstanceModel.ID.eq(occupant))
            .assign(InstanceModel.SERVER_ID, hostId)
            .assign(InstanceModel.QUOTA_BUCKET, bucketOne)
            .bypassBehaviours()
            .updateAll();
        String refusal = ApiSupport.shippedText(PlacementRefusal.DEDICATED_TO_OTHER.text().withArg("name", hostName));
        String transport = "&" + ApiSupport.fromTemplateTransport();
        try {
            // 1. The operator's own create (no project chosen): the host carries project one's workload, so it is
            //    offered disabled with the dedication refusal.
            navigateToApp("/admin/instances/new");
            waitForHydration();
            page.locator("pl-choice-card[data-value='hohenheim:docker_container']").click();
            waitForReactiveIdle();
            assertThat(hostOption(hostSelect, popup, hostId).getAttribute("aria-disabled"))
                .as("step 1: the operator's own create is refused the dedicated host").isEqualTo("true");
            assertThat(hostOption(hostSelect, popup, hostId).innerText()).as("step 1: in the gate's words")
                .contains(refusal);
            page.keyboard().press("Escape");

            // 2. Choosing project one judges the host for project one's bucket: offered, live, without a reload.
            choose(projectSelect, popup, projectOneId);
            page.waitForCondition(() -> !"true".equals(
                hostOption(hostSelect, popup, hostId).getAttribute("aria-disabled")));
            page.keyboard().press("Escape");

            // 3. Project two is another owner again.
            choose(projectSelect, popup, projectTwoId);
            page.waitForCondition(() -> "true".equals(
                hostOption(hostSelect, popup, hostId).getAttribute("aria-disabled")));
            page.keyboard().press("Escape");

            // 4. A fresh form, no project but one of project one's environments: the create lands in project one, so
            //    the host is offered again.
            navigateToApp("/admin/instances/new");
            waitForHydration();
            page.locator("pl-choice-card[data-value='hohenheim:docker_container']").click();
            waitForReactiveIdle();
            choose(environmentSelect, popup, environmentId);
            page.waitForCondition(() -> !"true".equals(
                hostOption(hostSelect, popup, hostId).getAttribute("aria-disabled")));
            page.keyboard().press("Escape");

            // 5. The submits agree with the pick: refused for the operator, landing for project one and for its
            //    environment.
            HttpResponse<String> refused = httpPostForm("/admin/instances/new", "name=" + PREFIX + "ded-operator"
                + "&kind=hohenheim%3Adocker_container&server_id=" + hostId, sessionToken, csrfToken);
            assertThat(refused.statusCode()).as("step 5: the operator's create is refused").isNotIn(302, 303);
            assertThat(refused.body()).as("step 5: in the words the pick showed").contains(refusal);
            HttpResponse<String> intoProject = httpPostForm("/admin/instances/new", "name=" + PREFIX
                + "ded-project&kind=hohenheim%3Adocker_container&server_id=" + hostId + "&project_id="
                + projectOneId, sessionToken, csrfToken);
            assertThat(intoProject.statusCode()).as("step 5: project one's create lands").isIn(302, 303);
            HttpResponse<String> intoEnvironment = httpPostForm("/admin/instances/new", "name=" + PREFIX
                + "ded-environment&kind=hohenheim%3Adocker_container&server_id=" + hostId + "&environment_id="
                + environmentId, sessionToken, csrfToken);
            assertThat(intoEnvironment.statusCode()).as("step 5: its environment's create lands").isIn(302, 303);
            assertThat(instances.find().where(InstanceModel.NAME.startsWith(PREFIX + "ded-")).count())
                .as("step 5: exactly the two accepted creates landed").isEqualTo(2);

            // 6. Creating from a template judges its host pick the same way, by the project chosen beside it.
            navigateToApp("/" + HohenheimSlugs.ADMIN + "/" + HohenheimSlugs.INSTANCES_FROM_TEMPLATE
                + "?template=" + templateId);
            waitForHydration();
            String templateHost = "pl-select[name='serverId']";
            assertThat(hostOption(templateHost, popup, hostId).getAttribute("aria-disabled"))
                .as("step 6: the template create refuses the operator the dedicated host").isEqualTo("true");
            assertThat(hostOption(templateHost, popup, hostId).innerText()).as("step 6: with its reason")
                .contains(refusal);
            page.keyboard().press("Escape");
            choose("pl-select[name='projectId']", popup, projectOneId);
            page.waitForCondition(() -> !"true".equals(
                hostOption(templateHost, popup, hostId).getAttribute("aria-disabled")));
            page.keyboard().press("Escape");

            // 7. And its submit agrees: refused for the operator, landing for project one.
            String target = ApiSupport.fromTemplateTarget(HohenheimSlugs.ADMIN, templateId);
            HttpResponse<String> templateRefused = httpPostForm(target, "name=" + PREFIX + "ded-template-operator"
                + "&serverId=" + hostId + transport, sessionToken, csrfToken);
            assertThat(templateRefused.statusCode()).as("step 7: the operator's template create is refused")
                .isEqualTo(422);
            HttpResponse<String> templateLanded = httpPostForm(target, "name=" + PREFIX + "ded-template-project"
                + "&serverId=" + hostId + "&projectId=" + projectOneId + transport, sessionToken, csrfToken);
            assertThat(templateLanded.statusCode()).as("step 7: project one's template create lands")
                .isIn(302, 303);
        } finally {
            for (Row row : instances.find().withTrashed().where(InstanceModel.NAME.startsWith(PREFIX + "ded"))
                    .all()) {
                HardDeletes.byId(instances, row.get(InstanceModel.ID));
            }
            servers.delete(hostId);
        }
    }

    /** Opens the select when it is closed and answers its option for that value. */
    private Locator hostOption(String select, String popup, int value) {
        if (page.locator(popup).count() == 0) {
            page.click(select + " .pl-select-field");
        }
        String option = popup + " div[role='option'][data-value='" + value + "']";
        page.waitForSelector(option);
        return page.locator(option);
    }

    /** Chooses one value of a select and waits for the form to settle. */
    private void choose(String select, String popup, int value) {
        page.click(select + " .pl-select-field");
        String option = popup + " div[role='option'][data-value='" + value + "']";
        page.waitForSelector(option);
        page.click(option);
        waitForReactiveIdle();
    }

    /** Environments group ONLY what their project owns, and their variables merge under. */
    @Test
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
        long operatorUsedBefore = OwnerBudget.INSTANCES.usedBy("");
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
        assertThat(OwnerBudget.INSTANCES.usedBy(""))
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
    void projectDeletionIsGuardedAndTearsDownItsAuthFootprint() {
        // 1. Project two owns gamma: deletion is refused.
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
        assertThat(Models.get(PermissionGroupModel.class).findById(groupId))
            .as("step 2: and the group row exists").isNotNull();
        // 3. Before it goes: the group SAYS where it came from, so /admin/roles no longer
        //    lists a bare "project-..." row with an empty description column.
        Row group = Models.get(PermissionGroupModel.class).findById(groupId);
        assertThat((String) group.get(PermissionGroupModel.DESCRIPTION))
            .as("step 3: the auto-created role names its owning project")
            .contains(PREFIX + "throwaway");

        // 4. Deleting that role on its own is REFUSED while the project points at it --
        //    it used to succeed and leave the project pointing at nothing.
        Throwable orphaning = catchThrowable(() ->
            Models.get(PermissionGroupModel.class).delete(groupId));
        assertThat(violationKeys(orphaning))
            .as("step 4: a project-owned role cannot be deleted from the roles surface")
            .contains("role_owned_by_project");
        assertThat(Models.get(PermissionGroupModel.class).findById(groupId))
            .as("step 4: and the row is still there").isNotNull();

        // 5. Renaming the project moves the provenance sentence with it.
        Row renamed = Models.get(ProjectModel.class).findById(throwawayId);
        renamed.set(ProjectModel.NAME, PREFIX + "renamed");
        Models.get(ProjectModel.class).save(renamed);
        assertThat((String) Models.get(PermissionGroupModel.class).findById(groupId)
                .get(PermissionGroupModel.DESCRIPTION))
            .as("step 5: the description follows the project name")
            .contains(PREFIX + "renamed");

        Models.get(ProjectModel.class).delete(throwawayId);
        assertThat(Models.get(PermissionGroupModel.class).findById(groupId))
            .as("step 6: deleting the project deleted its group explicitly")
            .isNull();
    }

    /**
     * The roles SURFACE agrees with the guard: a project-owned role is named as managed
     * and offers no delete at all, while an operator-created role keeps both.
     */
    @Test
    void projectOwnedRolesAreNamedAndUndeletableOnTheRolesSurface() throws Exception {
        int ownedProjectId = project(PREFIX + "surface");
        Row surfaceProject = Models.get(ProjectModel.class).findById(ownedProjectId);
        Integer ownedGroupId = surfaceProject.get(ProjectModel.GROUP_ID);
        assertThat(ownedGroupId).as("step 0: the project has its backing group").isNotNull();

        // A hand-made role is the counterfactual: everything asserted below must be
        // TRUE of it, or the assertions are only measuring an empty page.
        Row plain = Models.get(PermissionGroupModel.class).createEmptyRow();
        plain.set(PermissionGroupModel.SLUG, PREFIX + "plain");
        plain.set(PermissionGroupModel.TITLE, PREFIX + "plain");
        Models.get(PermissionGroupModel.class).save(plain);
        Integer plainGroupId = plain.get(PermissionGroupModel.ID);

        // 1. The list declares the managed-by column and names the owning project.
        HttpResponse<String> list = adminGet("/admin/roles");
        assertThat(list.statusCode()).as("step 1: the roles list renders").isEqualTo(200);
        assertThat(list.body())
            .as("step 1: the roles table carries the managed-by column")
            .contains("data-column=\"managed_by\"");
        assertThat(list.body())
            .as("step 1: and the owning project is named on the page")
            .contains("Managed by project " + PREFIX + "surface");

        // 2. The delete affordance is GONE for the owned role -- the row's delete
        //    operation never renders for it -- while the hand-made role still offers its own.
        assertThat(list.body())
            .as("step 2: no delete affordance for the project-owned role")
            .doesNotContainPattern(roleDelete(ownedGroupId));
        assertThat(list.body())
            .as("step 2: and the counterfactual proves the assertion can see one")
            .containsPattern(roleDelete(plainGroupId));

        // 3. The record page says WHY, and drops its Delete for the same reason.
        HttpResponse<String> detail = adminGet("/admin/roles/" + ownedGroupId);
        assertThat(detail.statusCode()).as("step 3: the record page renders").isEqualTo(200);
        assertThat(detail.body())
            .as("step 3: the record page carries the managed-role notice")
            .contains("maintained automatically");
        assertThat(detail.body())
            .as("step 3: and offers no delete")
            .doesNotContainPattern(roleDelete(ownedGroupId));
        assertThat(adminGet("/admin/roles/" + plainGroupId).body())
            .as("step 3: while the hand-made role's page still does")
            .containsPattern(roleDelete(plainGroupId));

        // 4. The refusal is not cosmetic: the submit is refused too, and the row stays.
        HttpResponse<String> deleted = httpPostForm(
            "/admin/roles/" + ownedGroupId + "/delete", "", sessionToken, csrfToken);
        assertThat(deleted.statusCode())
            .as("step 4: the delete submit is refused, not merely hidden")
            .isIn(403, 404);
        assertThat(Models.get(PermissionGroupModel.class).findById(ownedGroupId))
            .as("step 4: and the role is still there").isNotNull();

        Models.get(PermissionGroupModel.class).delete(plainGroupId);
        Models.get(ProjectModel.class).delete(ownedProjectId);
    }

    /** The roles resource's delete: zenit-auth's delete_role operation placed on the record, invoked by its id. */
    private static Pattern roleDelete(int roleId) {
        return Pattern.compile(Pattern.quote("/admin/roles/invoke/zenit.delete_role?ids=" + roleId) + "(?!\\d)");
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
