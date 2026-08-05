package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.DeploymentModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
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

/**
 * The PaaS automation API: proves it is the SAME door as the admin/manage surfaces --
 * one visibility walk, one refusal vocabulary, no existence oracle, and secrets that
 * are write-only over the wire.
 *
 * No daemon is needed: every act exercised refuses or answers before a driver call,
 * except the instance-log tail whose named refusal IS the assertion.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaasApiTest extends HohenheimTestBase {

    private static final String PREFIX = "paas-api-";
    private static final String VAR_PREFIX = "PAAS_API_";
    private static final String PLANTED_ENV_SECRET = "paas-env-s3cr3t-value";
    private static final String PLANTED_SECRET_VALUE = "paas-hunter2-secret";

    private static Integer tenantAId;
    private static Integer tenantBId;

    private static Integer siteAId;
    private static Integer siteBId;
    private static Integer staticSiteId;
    private static Integer instanceAId;

    private static Integer projectOneId;
    private static Integer projectTwoId;
    private static Integer environmentId;

    private static Integer releaseOfAId;
    private static Integer releaseOfBId;
    private static Integer buildOfAId;
    private static Integer deploymentOfAId;

    private static String keyPaasA;
    private static String keyPaasB;
    private static String keyNarrowA;
    private static String sessionA;

    @BeforeAll
    static void seed() {
        tenantAId = user("paas-tenant-a@surface.test", "Paas Tenant A");
        tenantBId = user("paas-tenant-b@surface.test", "Paas Tenant B");

        siteAId = site(PREFIX + "alpha", "hohenheim:docker");
        siteBId = site(PREFIX + "bravo", "hohenheim:docker");
        staticSiteId = site(PREFIX + "static", "hohenheim:static");
        RecordGrants.grant("user", tenantAId, SiteModel.MODEL_ID, siteAId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantAId, SiteModel.MODEL_ID, staticSiteId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantBId, SiteModel.MODEL_ID, siteBId,
            HohenheimAccess.MANAGE, true);

        instanceAId = instance(PREFIX + "workload");
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.MANAGE, true);

        projectOneId = project(PREFIX + "one");
        projectTwoId = project(PREFIX + "two");
        Projects.addMember(Models.get(ProjectModel.class).findById(projectOneId), tenantAId);
        Projects.addMember(Models.get(ProjectModel.class).findById(projectTwoId), tenantBId);

        Row environment = Models.get(EnvironmentModel.class).createEmptyRow();
        environment.set(EnvironmentModel.PROJECT_ID, projectOneId);
        environment.set(EnvironmentModel.NAME, PREFIX + "production");
        Models.get(EnvironmentModel.class).save(environment);
        environmentId = environment.get(EnvironmentModel.ID);

        releaseOfAId = releaseOperation(siteAId, "release-step-log-of-alpha");
        releaseOfBId = releaseOperation(siteBId, "release-step-log-of-bravo");
        buildOfAId = buildOperation(siteAId, "build-log-of-alpha");
        deploymentOfAId = deployment(siteAId, "deploy-log-of-alpha");

        String siteScope = CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimAccess.MANAGE);
        String instanceScope = CapabilityScopes.format(InstanceModel.MODEL_ID,
            HohenheimAccess.MANAGE);
        keyPaasA = ApiKeyService.create(tenantAId, PREFIX + "a",
            List.of(siteScope, instanceScope), null).plaintext();
        keyPaasB = ApiKeyService.create(tenantBId, PREFIX + "b",
            List.of(siteScope, instanceScope), null).plaintext();
        // A key of the SAME owner narrowed to an unrelated vocabulary: everything it
        // touches below must answer as if the owner's grants did not exist.
        keyNarrowA = ApiKeyService.create(tenantAId, PREFIX + "a-narrow",
            List.of("shortlink.*"), null).plaintext();

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, tenantAId.longValue());
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);
        sessionA = session.id();
    }

    @AfterAll
    static void cleanUp() {
        deleteWhere(Models.get(InstanceVariableModel.class),
            Models.get(InstanceVariableModel.class).find()
                .where(InstanceVariableModel.KEY.startsWith(VAR_PREFIX)).all(),
            InstanceVariableModel.ID.getName());
        deleteWhere(Models.get(ReleaseOperationModel.class),
            Models.get(ReleaseOperationModel.class).find()
                .where(ReleaseOperationModel.FOR_ID.in(siteAId, siteBId)).all(),
            ReleaseOperationModel.ID.getName());
        deleteWhere(Models.get(BuildOperationModel.class),
            Models.get(BuildOperationModel.class).find()
                .where(BuildOperationModel.FOR_ID.eq(siteAId)).all(),
            BuildOperationModel.ID.getName());
        deleteWhere(Models.get(DeploymentModel.class),
            Models.get(DeploymentModel.class).find()
                .where(DeploymentModel.SITE_ID.eq(siteAId)).all(),
            DeploymentModel.ID.getName());
        deleteWhere(Models.get(InstanceModel.class),
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.startsWith(PREFIX)).all(),
            InstanceModel.ID.getName());
        deleteWhere(Models.get(SiteModel.class),
            Models.get(SiteModel.class).find()
                .where(SiteModel.NAME.startsWith(PREFIX)).all(),
            SiteModel.ID.getName());
        deleteWhere(Models.get(EnvironmentModel.class),
            Models.get(EnvironmentModel.class).find()
                .where(EnvironmentModel.NAME.startsWith(PREFIX)).all(),
            EnvironmentModel.ID.getName());
        deleteWhere(Models.get(ProjectModel.class),
            Models.get(ProjectModel.class).find()
                .where(ProjectModel.NAME.startsWith(PREFIX)).all(),
            ProjectModel.ID.getName());
    }

    private static void deleteWhere(Model model, List<Row> rows, String idField) {
        for (Row row : rows) {
            model.delete(row.get(idField));
        }
    }

    // -- fixtures --------------------------------------------------------------

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

    private static int site(String name, String type) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.SITE_TYPE, type);
        row.set(SiteModel.ENABLED, false);
        row.set(SiteModel.SETTINGS, new LinkedHashMap<>(Map.of(
            "image", "alpine", "tag", "latest",
            "environment_variables", Map.of("PLANTED", PLANTED_ENV_SECRET))));
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
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

    private static int project(String name) {
        Row row = Models.get(ProjectModel.class).createEmptyRow();
        row.set(ProjectModel.NAME, name);
        Models.get(ProjectModel.class).save(row);
        return row.get(ProjectModel.ID);
    }

    private static int releaseOperation(int siteId, String stepLog) {
        Row op = Models.get(ReleaseOperationModel.class).createEmptyRow();
        op.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
        op.set(ReleaseOperationModel.FOR_MODEL, SiteModel.MODEL_ID.toString());
        op.set(ReleaseOperationModel.FOR_ID, siteId);
        op.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.STATUS_SUCCEEDED);
        op.set(ReleaseOperationModel.STEP_LOG, stepLog);
        Models.get(ReleaseOperationModel.class).save(op);
        return op.get(ReleaseOperationModel.ID);
    }

    private static int buildOperation(int siteId, String log) {
        Row op = Models.get(BuildOperationModel.class).createEmptyRow();
        op.set(BuildOperationModel.BUILDER_KIND, BuildOperationModel.KIND_DOCKERFILE);
        op.set(BuildOperationModel.FOR_MODEL, SiteModel.MODEL_ID.toString());
        op.set(BuildOperationModel.FOR_ID, siteId);
        op.set(BuildOperationModel.STATUS, BuildOperationModel.STATUS_SUCCEEDED);
        op.set(BuildOperationModel.LOG, log);
        Models.get(BuildOperationModel.class).save(op);
        return op.get(BuildOperationModel.ID);
    }

    private static int deployment(int siteId, String log) {
        Row op = Models.get(DeploymentModel.class).createEmptyRow();
        op.set(DeploymentModel.SITE_ID, siteId);
        op.set(DeploymentModel.STATUS, DeploymentModel.STATUS_SUCCESS);
        op.set(DeploymentModel.LOG, log);
        Models.get(DeploymentModel.class).save(op);
        return op.get(DeploymentModel.ID);
    }

    // -- transport -------------------------------------------------------------

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> keyGet(String key, String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("X-Api-Key", key)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> keyPost(String key, String path, String body) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("X-Api-Key", key)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    // -- the journeys ----------------------------------------------------------

    /** Keys only; the inventory is exactly the granted one; scopes narrow it away. */
    @Test
    @Order(1)
    void theSurfaceIsKeyOnlyAndScopeNarrowed() throws Exception {
        // 1. A browser session is not an automation credential.
        HttpResponse<String> viaCookie = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/api/v1/sites"))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionA)
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(viaCookie.statusCode())
            .as("step 1: a session cookie is refused on the PaaS surface")
            .isEqualTo(403);

        // 2. The site list is a CONTENT assertion: A's sites in, B's out, and the
        //    projection is a whitelist -- the settings' env material never leaves.
        HttpResponse<String> sites = keyGet(keyPaasA, "/api/v1/sites");
        assertThat(sites.statusCode()).as("step 2: the key's site list renders").isEqualTo(200);
        assertThat(sites.body()).as("step 2: tenant A's site is projected")
            .contains(PREFIX + "alpha");
        assertThat(sites.body()).as("step 2: tenant B's site is NOT in the response")
            .doesNotContain(PREFIX + "bravo");
        assertThat(sites.body())
            .as("step 2: no settings material (env values) in the projection")
            .doesNotContain(PLANTED_ENV_SECRET);

        // 3. Projects list by membership: A sees project one, never project two.
        HttpResponse<String> projects = keyGet(keyPaasA, "/api/v1/projects");
        assertThat(projects.statusCode()).as("step 3: the project list renders").isEqualTo(200);
        assertThat(projects.body()).as("step 3: A's project is listed")
            .contains(PREFIX + "one");
        assertThat(projects.body()).as("step 3: B's project is not")
            .doesNotContain(PREFIX + "two");
        assertThat(projects.body()).as("step 3: with its environment")
            .contains(PREFIX + "production");

        // 4. The SAME owner behind a scope-narrowed key sees NOTHING: the capability
        //    walk narrows sites, and the membership listing demands the PaaS vocabulary.
        HttpResponse<String> narrowSites = keyGet(keyNarrowA, "/api/v1/sites");
        assertThat(narrowSites.statusCode()).as("step 4: the narrowed key still answers")
            .isEqualTo(200);
        assertThat(narrowSites.body())
            .as("step 4: but its owner's sites are narrowed away")
            .doesNotContain(PREFIX + "alpha");
        HttpResponse<String> narrowProjects = keyGet(keyNarrowA, "/api/v1/projects");
        assertThat(narrowProjects.body())
            .as("step 4: and the membership listing is narrowed away with them")
            .doesNotContain(PREFIX + "one");
    }

    /** COUNTERFACTUAL: unowned and nonexistent are one answer, and nothing happens. */
    @Test
    @Order(2)
    void unownedAndAbsentAnswerIdenticallyWithNoSideEffect() throws Exception {
        int absentId = 900_000_101;
        assertThat(Models.get(SiteModel.class).findById(absentId))
            .as("step 1: the control id really does not exist").isNull();

        HttpResponse<String> foreign = keyGet(keyPaasA, "/api/v1/sites/" + siteBId);
        HttpResponse<String> absent = keyGet(keyPaasA, "/api/v1/sites/" + absentId);
        assertThat(foreign.statusCode()).as("step 2: an unowned site is 404").isEqualTo(404);
        assertThat(absent.statusCode()).as("step 2: an absent site is 404").isEqualTo(404);
        assertThat(foreign.body()).as("step 2: the bodies are identical -- no oracle")
            .isEqualTo(absent.body());

        // 3. A deploy POST on the unowned site answers the same 404 AND performed no
        //    work: no deployment record, no release operation appears for site B.
        long deploysBefore = Models.get(DeploymentModel.class).find()
            .where(DeploymentModel.SITE_ID.eq(siteBId)).count();
        long releasesBefore = Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_ID.eq(siteBId)).count();
        HttpResponse<String> deployForeign = keyPost(keyPaasA,
            "/api/v1/sites/" + siteBId + "/deploy", "");
        HttpResponse<String> deployAbsent = keyPost(keyPaasA,
            "/api/v1/sites/" + absentId + "/deploy", "");
        assertThat(deployForeign.statusCode()).as("step 3: deploy on an unowned site is 404")
            .isEqualTo(404);
        assertThat(deployForeign.body()).as("step 3: identical to the absent id's answer")
            .isEqualTo(deployAbsent.body());
        HttpResponse<String> rollbackForeign = keyPost(keyPaasA,
            "/api/v1/sites/" + siteBId + "/rollback", "");
        assertThat(rollbackForeign.statusCode()).as("step 3: rollback likewise")
            .isEqualTo(404);
        assertThat(Models.get(DeploymentModel.class).find()
                .where(DeploymentModel.SITE_ID.eq(siteBId)).count())
            .as("step 3: and no deployment was recorded for the unowned site")
            .isEqualTo(deploysBefore);
        assertThat(Models.get(ReleaseOperationModel.class).find()
                .where(ReleaseOperationModel.FOR_ID.eq(siteBId)).count())
            .as("step 3: and no release operation was recorded either")
            .isEqualTo(releasesBefore);

        // 4. A child record of ANOTHER site answers exactly like a missing one, so
        //    operation ids stay unenumerable across sites.
        HttpResponse<String> crossChild = keyGet(keyPaasA,
            "/api/v1/sites/" + siteAId + "/releases/" + releaseOfBId);
        HttpResponse<String> absentChild = keyGet(keyPaasA,
            "/api/v1/sites/" + siteAId + "/releases/" + absentId);
        assertThat(crossChild.statusCode())
            .as("step 4: another site's release id under my site is 404").isEqualTo(404);
        assertThat(crossChild.body()).as("step 4: identical to an absent one")
            .isEqualTo(absentChild.body());
        assertThat(crossChild.body()).as("step 4: and leaks no step log")
            .doesNotContain("release-step-log-of-bravo");

        // 5. Environments: a non-member's key gets the SAME uniform 404.
        HttpResponse<String> envForeign = keyGet(keyPaasB,
            "/api/v1/environments/" + environmentId + "/variables");
        assertThat(envForeign.statusCode())
            .as("step 5: a non-member cannot see the environment, uniformly").isEqualTo(404);
    }

    /** Deploy/rollback dispatch to the SAME engines the UI uses, refusals named. */
    @Test
    @Order(3)
    void deployAndRollbackAreTheSameDoorAsTheUi() throws Exception {
        // 1. This docker site is not git-provisioned (no live handler): deploy has
        //    nothing to enqueue and says so by name -- exactly the HTML lane's shape,
        //    where the Deployments tab only exists for git sources.
        HttpResponse<String> deploy = keyPost(keyPaasA,
            "/api/v1/sites/" + siteAId + "/deploy", "");
        assertThat(deploy.statusCode()).as("step 1: refused, typed").isEqualTo(422);
        assertThat(deploy.body()).as("step 1: named").contains("deploy_not_available");

        // 2. Rollback on a docker site DISPATCHES to the release engine: with no
        //    retained release the engine's own named refusal comes back, which proves
        //    the call reached SiteReleases rather than a re-implementation.
        long opsBefore = Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_ID.eq(siteAId)).count();
        HttpResponse<String> rollback = keyPost(keyPaasA,
            "/api/v1/sites/" + siteAId + "/rollback", "");
        assertThat(rollback.statusCode()).as("step 2: refused, typed").isEqualTo(422);
        assertThat(rollback.body())
            .as("step 2: with the release engine's own violation")
            .contains("release_no_rollback_target");
        assertThat(Models.get(ReleaseOperationModel.class).find()
                .where(ReleaseOperationModel.FOR_ID.eq(siteAId)).count())
            .as("step 2: and no operation row was minted for the refused rollback")
            .isEqualTo(opsBefore);

        // 3. A non-docker site without a git wrapper has no rollback lane at all.
        HttpResponse<String> staticRollback = keyPost(keyPaasA,
            "/api/v1/sites/" + staticSiteId + "/rollback", "");
        assertThat(staticRollback.statusCode()).as("step 3: refused, typed").isEqualTo(422);
        assertThat(staticRollback.body()).as("step 3: named")
            .contains("rollback_not_available");
    }

    /** Secrets are WRITE-ONLY over the API; plain values round-trip; deletes stick. */
    @Test
    @Order(4)
    void variablesRoundTripAndSecretsNeverComeBack() throws Exception {
        String base = "/api/v1/instances/" + instanceAId + "/variables";

        // 1. A plain value round-trips visibly.
        HttpResponse<String> setPlain = keyPost(keyPaasA, base,
            "key=" + VAR_PREFIX + "PLAIN&value=plain-visible-value");
        assertThat(setPlain.statusCode()).as("step 1: plain set accepted").isEqualTo(200);
        HttpResponse<String> listed = keyGet(keyPaasA, base);
        assertThat(listed.body()).as("step 1: the plain value is readable")
            .contains(VAR_PREFIX + "PLAIN").contains("plain-visible-value");

        // 2. A secret is accepted, STORED (the deploy reader sees it), and NEVER
        //    echoed -- not in the set response, not in any later read.
        HttpResponse<String> setSecret = keyPost(keyPaasA, base,
            "key=" + VAR_PREFIX + "TOKEN&kind=secret&value=" + PLANTED_SECRET_VALUE);
        assertThat(setSecret.statusCode()).as("step 2: secret set accepted").isEqualTo(200);
        assertThat(setSecret.body()).as("step 2: the set response never echoes the value")
            .doesNotContain(PLANTED_SECRET_VALUE);
        HttpResponse<String> relisted = keyGet(keyPaasA, base);
        assertThat(relisted.body()).as("step 2: the secret row is listed by key")
            .contains(VAR_PREFIX + "TOKEN").contains("has_value");
        assertThat(relisted.body()).as("step 2: but its value has NO representation")
            .doesNotContain(PLANTED_SECRET_VALUE);
        assertThat(new InstanceVariables().valuesFor(instanceAId))
            .as("step 2: while env injection (the one legitimate reader) sees it")
            .containsEntry(VAR_PREFIX + "TOKEN", PLANTED_SECRET_VALUE);

        // 3. Delete removes the row; a second delete is a named refusal.
        HttpResponse<String> deleted = keyPost(keyPaasA, base + "/delete",
            "key=" + VAR_PREFIX + "TOKEN");
        assertThat(deleted.statusCode()).as("step 3: delete accepted").isEqualTo(200);
        assertThat(new InstanceVariables().valuesFor(instanceAId))
            .as("step 3: the stored value is gone")
            .doesNotContainKey(VAR_PREFIX + "TOKEN");
        HttpResponse<String> again = keyPost(keyPaasA, base + "/delete",
            "key=" + VAR_PREFIX + "TOKEN");
        assertThat(again.statusCode()).as("step 3: a second delete is 422").isEqualTo(422);
        assertThat(again.body()).as("step 3: named").contains("variable_not_found");

        // 4. Malformed writes are named refusals, not silent coercions.
        HttpResponse<String> badKind = keyPost(keyPaasA, base,
            "key=" + VAR_PREFIX + "X&kind=mystery&value=v");
        assertThat(badKind.statusCode()).as("step 4: unknown kind is 422").isEqualTo(422);
        assertThat(badKind.body()).as("step 4: named").contains("variable_kind_unknown");
        HttpResponse<String> noKey = keyPost(keyPaasA, base, "value=v");
        assertThat(noKey.statusCode()).as("step 4: missing key is 422").isEqualTo(422);
        assertThat(noKey.body()).as("step 4: named").contains("variable_key_required");

        // 5. The environment lane rides the SAME mechanism and the same masking: a
        //    member sets a secret on the environment, reads back only its existence.
        String envBase = "/api/v1/environments/" + environmentId + "/variables";
        HttpResponse<String> envSet = keyPost(keyPaasA, envBase,
            "key=" + VAR_PREFIX + "ENV&kind=secret&value=" + PLANTED_SECRET_VALUE);
        assertThat(envSet.statusCode()).as("step 5: member's env secret accepted")
            .isEqualTo(200);
        HttpResponse<String> envListed = keyGet(keyPaasA, envBase);
        assertThat(envListed.body()).as("step 5: listed by key, value withheld")
            .contains(VAR_PREFIX + "ENV").doesNotContain(PLANTED_SECRET_VALUE);
        assertThat(new InstanceVariables().valuesForEnvironment(environmentId))
            .as("step 5: while the deploy baseline reader sees it")
            .containsEntry(VAR_PREFIX + "ENV", PLANTED_SECRET_VALUE);
    }

    /** Operation records and logs are readable exactly within the granted scope. */
    @Test
    @Order(5)
    void operationRecordsAndLogsAnswerWithinScope() throws Exception {
        // 1. The three record lanes of the owned site render their seeded rows.
        HttpResponse<String> releases = keyGet(keyPaasA,
            "/api/v1/sites/" + siteAId + "/releases");
        assertThat(releases.statusCode()).as("step 1: releases list").isEqualTo(200);
        assertThat(releases.body()).as("step 1: carries the seeded operation")
            .contains(String.valueOf(releaseOfAId));
        assertThat(releases.body())
            .as("step 1: the LIST omits step logs (they ride the detail read)")
            .doesNotContain("release-step-log-of-alpha");

        HttpResponse<String> release = keyGet(keyPaasA,
            "/api/v1/sites/" + siteAId + "/releases/" + releaseOfAId);
        assertThat(release.body()).as("step 1: the detail carries the step log")
            .contains("release-step-log-of-alpha");

        HttpResponse<String> buildLog = keyGet(keyPaasA,
            "/api/v1/sites/" + siteAId + "/builds/" + buildOfAId + "/log");
        assertThat(buildLog.body()).as("step 1: the build log reads back")
            .contains("build-log-of-alpha");

        HttpResponse<String> deployLog = keyGet(keyPaasA,
            "/api/v1/sites/" + siteAId + "/deployments/" + deploymentOfAId + "/log");
        assertThat(deployLog.body()).as("step 1: the git deployment log reads back")
            .contains("deploy-log-of-alpha");

        // 2. The instance log tail is a NAMED refusal when the workload has no
        //    answerable container -- never a 500, never a silent empty success.
        HttpResponse<String> logs = keyGet(keyPaasA,
            "/api/v1/instances/" + instanceAId + "/logs");
        assertThat(logs.statusCode())
            .as("step 2: a container-less workload's tail is a typed refusal")
            .isEqualTo(422);
        assertThat(logs.body()).as("step 2: named").contains("logs_unavailable");

        // 3. The narrowed key reads NONE of it: the same paths answer 404, proving the
        //    operation lanes ride the site visibility walk rather than their own.
        assertThat(keyGet(keyNarrowA, "/api/v1/sites/" + siteAId + "/releases").statusCode())
            .as("step 3: releases are invisible to the narrowed key").isEqualTo(404);
        assertThat(keyGet(keyNarrowA,
                "/api/v1/sites/" + siteAId + "/builds/" + buildOfAId + "/log").statusCode())
            .as("step 3: build logs likewise").isEqualTo(404);
    }
}
