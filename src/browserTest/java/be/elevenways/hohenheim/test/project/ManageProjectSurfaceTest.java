package be.elevenways.hohenheim.test.project;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.ApiKeyPrincipal;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant-facing project surface at /manage: a MEMBER sees their own project and
 * who else is in it, sees nothing of anyone else's, and cannot write any of it.
 *
 * Plus the exposure this tier keeps re-opening: project membership is grant-derived, so
 * the capability walk -- the one place zenit-auth applies a key's scope narrowing --
 * never runs for a membership listing, and an unguarded listing hands a key its owner's
 * whole project inventory. That is asserted against the shared POLICY rather than
 * through these pages, for the reason spelled out on the second test.
 */
class ManageProjectSurfaceTest extends HohenheimTestBase {

    private static final String PREFIX = "mps-";

    private static Integer memberAId;
    private static Integer memberBId;
    private static Integer outsiderId;
    private static String sessionA;
    private static String csrfA;
    private static String sessionB;
    private static String sessionOutsider;

    private static String keyPaasA;
    private static String keyNarrowA;

    private static Integer projectOneId;
    private static Integer projectTwoId;

    @BeforeAll
    static void seed() {
        memberAId = user("mps-a@project.test", PREFIX + "Member Alpha");
        memberBId = user("mps-b@project.test", PREFIX + "Member Bravo");
        outsiderId = user("mps-out@project.test", PREFIX + "Outsider");

        sessionA = session(memberAId, csrf -> csrfA = csrf);
        sessionB = session(memberBId, csrf -> { });
        sessionOutsider = session(outsiderId, csrf -> { });

        // Both projects own NOTHING. That is deliberate: the only thing that can put
        // member A inside /manage is the project membership itself, so step 1 is also
        // the eligibility proof.
        projectOneId = project(PREFIX + "one");
        projectTwoId = project(PREFIX + "two");
        Projects.addMember(Models.get(ProjectModel.class).findById(projectOneId), memberAId);
        Projects.addMember(Models.get(ProjectModel.class).findById(projectTwoId), memberBId);

        keyPaasA = ApiKeyService.create(memberAId, PREFIX + "paas", List.of(
            CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimAccess.MANAGE),
            CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.MANAGE)),
            null).plaintext();
        keyNarrowA = ApiKeyService.create(memberAId, PREFIX + "narrow",
            List.of("shortlink.*"), null).plaintext();
    }

    @AfterAll
    static void cleanUp() {
        Model projects = Models.get(ProjectModel.class);
        for (Row row : projects.find().where(ProjectModel.NAME.startsWith(PREFIX)).all()) {
            projects.delete(row.get(ProjectModel.ID));
        }
        Model users = AuthModels.users();
        for (Row row : users.find().where(UserModel.DISPLAY_NAME.startsWith(PREFIX)).all()) {
            users.delete(row.get(UserModel.ID));
        }
    }

    @Test
    void aMemberSeesTheirProjectAndItsMembersAndNothingElseTheyDoNotOwn() throws Exception {
        // 1. POSITIVE ANCHOR and the eligibility proof in one: a member of a project
        //    that owns no site and no instance still reaches the delegated panel, and
        //    the list is exactly their project.
        HttpResponse<String> list = get("/manage/projects", sessionA);
        assertThat(list.statusCode())
            .as("step 1: membership alone admits a tenant to /manage").isEqualTo(200);
        assertThat(list.body()).as("step 1: their own project is listed")
            .contains(PREFIX + "one");
        assertThat(list.body()).as("step 1: another member's project is not")
            .doesNotContain(PREFIX + "two");

        // 2. The membership roster is scoped by the SAME enumeration: A learns who is
        //    in project one, and nothing about who is in project two.
        HttpResponse<String> members = get("/manage/project-members", sessionA);
        assertThat(members.statusCode()).as("step 2: the roster renders").isEqualTo(200);
        assertThat(members.body()).as("step 2: A sees themselves in their project")
            .contains(PREFIX + "Member Alpha").contains(PREFIX + "one");
        assertThat(members.body()).as("step 2: and never the other project's member")
            .doesNotContain(PREFIX + "Member Bravo");

        // 3. The mirror image, so step 2 is not just "A sees the first row of everything":
        //    B sees exactly the other side.
        HttpResponse<String> mirror = get("/manage/project-members", sessionB);
        assertThat(mirror.body()).as("step 3: B sees their own membership")
            .contains(PREFIX + "Member Bravo");
        assertThat(mirror.body()).as("step 3: and not A's").doesNotContain(PREFIX + "Member Alpha");

        // 4. An out-of-scope record reads as MISSING, not as forbidden.
        assertThat(get("/manage/projects/" + projectTwoId, sessionA).statusCode())
            .as("step 4: another project's id 404s for a non-member").isEqualTo(404);

        // 5. A principal in no project at all is refused the panel outright.
        assertThat(get("/manage/projects", sessionOutsider).statusCode())
            .as("step 5: no membership, no panel").isEqualTo(403);
    }

    /**
     * The scope narrowing is asserted on the POLICY, not through the CMS pages, and
     * deliberately so: every zenit-cms resource route is
     * {@code requiresInteractiveLogin()} (zenit {@code Endpoint.java:814}, consumed by
     * the authorization middleware), so an API key never reaches /manage at all. A
     * "narrowed key sees nothing at /manage" assertion would therefore pass with the
     * guard ripped out -- vacuous. Step 4 pins that enforcer so its removal is loud, and
     * steps 1-3 pin the guard itself where every future surface consumes it.
     */
    @Test
    void aScopeNarrowedKeyOfTheSameMemberEnumeratesNoProjects() throws Exception {
        // 1. POSITIVE ANCHOR: the member's own session sees their project through the
        //    same policy, so the refusals below are not refusing everything.
        assertThat(names(Projects.visibleTo(contextOf(new UserPrincipal(memberAId,
            PREFIX + "Member Alpha")))))
            .as("step 1: the member's own identity enumerates their project")
            .contains(PREFIX + "one").doesNotContain(PREFIX + "two");

        // 2. A key of the same owner CARRYING the vocabulary project-owned records
        //    answer to keeps that answer.
        assertThat(names(Projects.visibleTo(contextOf(key(List.of(
            CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimAccess.MANAGE)))))))
            .as("step 2: a covering key still enumerates its owner's project")
            .contains(PREFIX + "one");

        // 3. The SAME owner behind a key narrowed to an unrelated vocabulary enumerates
        //    NOTHING. Membership is grant-derived, so no capability walk narrows it --
        //    only this guard does.
        assertThat(Projects.visibleTo(contextOf(key(List.of("shortlink.*")))))
            .as("step 3: a narrowed key enumerates no project of its owner").isEmpty();
        assertThat(Projects.visibleTo(contextOf(key(List.of()))))
            .as("step 3: and an empty-scope key covers nothing at all").isEmpty();

        // 4. The layered enforcer, pinned: an API key cannot reach the CMS surface in the
        //    first place, so nobody later reads step 3 as the only thing standing between
        //    a key and /manage.
        assertThat(keyGet(keyNarrowA, "/manage/projects").statusCode())
            .as("step 4: a non-interactive credential is refused the panel outright")
            .isNotEqualTo(200);
        assertThat(keyGet(keyPaasA, "/manage/projects").statusCode())
            .as("step 4: covering scopes do not buy interactivity either")
            .isNotEqualTo(200);

        // 5. The JSON lane is the one an API key legitimately reaches, and the shared
        //    policy did not regress it.
        assertThat(keyGet(keyNarrowA, "/api/v1/projects").body())
            .as("step 5: the JSON lane stays narrowed").doesNotContain(PREFIX + "one");
        assertThat(keyGet(keyPaasA, "/api/v1/projects").body())
            .as("step 5: and the covering key still lists it").contains(PREFIX + "one");
    }

    private static ApiKeyPrincipal key(List<String> scopes) {
        return new ApiKeyPrincipal(memberAId.longValue(), PREFIX + "Member Alpha",
            1, PREFIX + "probe", scopes);
    }

    private static AccessContext contextOf(be.elevenways.zenit.common.security.Principal principal) {
        return AccessContext.of(TenantConduits.stubFor(principal));
    }

    private static List<String> names(List<Row> projects) {
        List<String> names = new java.util.ArrayList<>();
        for (Row project : projects) {
            names.add(String.valueOf((Object) project.get(ProjectModel.NAME)));
        }
        return names;
    }

    @Test
    void aMemberCannotWriteTheProjectAndTheRowIsUnchanged() throws Exception {
        String before = Models.get(ProjectModel.class).findById(projectOneId)
            .get(ProjectModel.NAME);

        // 1. The delegated surface offers no rename: a project's name is mirrored onto
        //    its backing permission group, which is auth-tier state.
        HttpResponse<String> update = post("/manage/projects/" + projectOneId,
            "name=" + PREFIX + "renamed-by-tenant", sessionA, csrfA);
        assertThat(update.statusCode())
            .as("step 1: the update route does not exist on the delegated surface")
            .isEqualTo(404);

        // 2. STATE, not status: a refusal that still wrote the row would pass a
        //    status-only assertion.
        assertThat((Object) Models.get(ProjectModel.class).findById(projectOneId)
            .get(ProjectModel.NAME))
            .as("step 2: the stored name is untouched").isEqualTo(before);

        // 3. Creation and deletion are off the same surface.
        assertThat(get("/manage/projects/new", sessionA).statusCode())
            .as("step 3: no create form").isEqualTo(404);
        assertThat(post("/manage/projects/" + projectOneId + "/delete", "",
            sessionA, csrfA).statusCode())
            .as("step 3: no delete route").isEqualTo(404);
        assertThat(Models.get(ProjectModel.class).findById(projectOneId))
            .as("step 3: and the project is still there").isNotNull();
    }

    // -- fixtures -------------------------------------------------------------

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> keyGet(String key, String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("X-Api-Key", key)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String session, String csrf)
            throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
                .header("X-Csrf-Token", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String session(int userId, java.util.function.Consumer<String> csrfSink) {
        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, (long) userId);
        String csrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, csrf);
        Zenit.getSessionStore().save(session);
        csrfSink.accept(csrf);
        return session.id();
    }

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
}
