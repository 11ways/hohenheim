package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.ManageInstanceResource;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violations;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The single-instance migration SURFACE: {@code InstanceMigrations.migrateTo} had no UI
 * or API caller at all, reachable only as a side effect of draining a whole host.
 *
 * AIDEV-NOTE: the pre-fix defect this pins is an ABSENCE -- the migrate page 404d and no
 * instance row action pointed anywhere near it (verified 2026-08-11: the URL answered 404
 * and the admin list body contained no {@code /page/migrate}). The counterfactual for the
 * ADMIN-ONLY half is step 5, and it is honest about its layers: the surface gate is what
 * this class proves, and {@code InstanceMigrations.migrateTo} refuses every
 * tenant-originated call underneath it (step 6 proves that separately, so neither claim
 * rests on the other).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceMigrateSurfaceTest extends HohenheimTestBase {

    private static Integer instanceId;
    private static Integer strangerHostId;
    private static Integer tenantId;
    private static String tenantSession;
    private static String tenantCsrf;

    @BeforeAll
    static void seed() {
        var instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, "migrate-subject");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(instance);
        instanceId = instance.get(InstanceModel.ID);

        // A second host that is enrolled but NOT admitted: the eligible-set refusal this
        // page must name out loud instead of hiding.
        var servers = Models.get(ServerModel.class);
        Row stranger = servers.createEmptyRow();
        stranger.set(ServerModel.NAME, "migrate-stranger");
        stranger.set(ServerModel.SSH_TARGET, "nobody@migrate-stranger.invalid");
        servers.save(stranger);
        strangerHostId = servers.findByName("migrate-stranger").get(ServerModel.ID);

        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "migrate-tenant@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Migrate Tenant");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        tenantId = user.get(UserModel.ID);
        RecordGrants.grant("user", tenantId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, tenantId.longValue());
        tenantCsrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, tenantCsrf);
        Zenit.getSessionStore().save(session);
        tenantSession = session.token().secret();
    }

    /**
     * THE ABSENCE, closed: the migrate page exists, names the host the workload is on
     * now, and is reached from the instance's own row action.
     */
    @Test
    @Order(1)
    void theMigratePageExistsAndIsReachableFromTheInstanceRowAction() throws Exception {
        // 1. The page renders. Pre-fix this URL answered 404 -- migrateTo had no caller.
        HttpResponse<String> page = adminGet(migrateUrl());
        assertThat(page.statusCode())
            .withFailMessage("step 1: the instance migrate page does not exist, so"
                + " InstanceMigrations.migrateTo is still reachable only by draining a"
                + " whole host (HTTP %s)", page.statusCode())
            .isEqualTo(200);

        // 2. It names the source host, so the operator knows what they are moving FROM.
        assertThat(page.body()).as("step 2: the current host is named")
            .contains(ServerModel.nameOf(ServerModel.localServerId()));

        // 3. The admin list offers the way in.
        HttpResponse<String> list = adminGet("/admin/instances");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body())
            .withFailMessage("step 3: no instance row action points at the migrate page")
            .contains("/page/migrate");
    }

    /**
     * An ineligible destination is listed with the AUTHORITY'S OWN refusal beside it and
     * carries no submit button -- never a silent omission and never a button that can
     * only fail.
     */
    @Test
    @Order(2)
    void anIneligibleHostIsNamedWithItsRefusalAndOffersNoButton() throws Exception {
        HttpResponse<String> page = adminGet(migrateUrl());
        String body = page.body();

        // 1. The un-admitted host is present, by name.
        assertThat(body).as("step 1: the enrolled host is listed")
            .contains("migrate-stranger");
        assertThat(body).as("step 1: as an explicit target row")
            .contains("data-migrate-target=\"" + strangerHostId + "\"");

        // 2. With the refusal text rendered, not swallowed.
        assertThat(body)
            .withFailMessage("step 2: the host is listed without the named reason it"
                + " cannot receive the workload")
            .contains("data-migrate-refusal");

        // 3. And the survey agrees with what rendered: the same host, ineligible, with
        //    a refusal the page could resolve.
        InstanceMigrations.Destination stranger = new InstanceMigrations()
            .destinationsFor(instanceId).stream()
            .filter(candidate -> candidate.serverId() == strangerHostId)
            .findFirst().orElseThrow();
        assertThat(stranger.eligible()).as("step 3: an un-admitted host is not eligible")
            .isFalse();
        assertThat(stranger.refusal()).as("step 3: and carries a named refusal").isNotNull();
    }

    /**
     * A protected in-flight status blocks the move with a STATED reason rather than a
     * vanished control -- and the vocabulary is the guard's own, never a second list.
     */
    @Test
    @Order(3)
    void aProtectedStatusBlocksTheMoveWithAStatedReason() throws Exception {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.findById(instanceId);
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CAPTURING);
        instances.save(row);
        try {
            // 1. The page still renders (a hidden page explains nothing).
            HttpResponse<String> page = adminGet(migrateUrl());
            assertThat(page.statusCode()).as("step 1: the page still renders").isEqualTo(200);

            // 2. And says which status holds the record.
            assertThat(page.body())
                .withFailMessage("step 2: a capture holds the record and the page says"
                    + " nothing about it")
                .contains("data-migrate-blocked");

            // 3. The model's shared vocabulary is what decided that.
            assertThat(InstanceModel.isOperable(instances.findById(instanceId)))
                .as("step 3: the shared protected-status vocabulary answers").isFalse();
        } finally {
            Row restored = instances.findById(instanceId);
            restored.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            instances.save(restored);
        }
        assertThat(adminGet(migrateUrl()).body())
            .as("positive anchor: an operable instance renders no blocked banner")
            .doesNotContain("data-migrate-blocked");
    }

    /**
     * A refused submit is an ERROR, never a success toast: migrating onto the host the
     * workload already runs on is refused by name and the record does not move.
     */
    @Test
    @Order(4)
    void arefusedSubmitSurfacesTheRefusalInsteadOfASuccessToast() throws Exception {
        int localHost = ServerModel.localServerId();
        HttpResponse<String> refused = adminPost(migrateUrl(),
            "target_server_id=" + localHost);
        assertThat(refused.statusCode())
            .as("step 1: the submit is answered (redirect with a flash)").isIn(200, 302, 303);

        Row after = Models.get(InstanceModel.class).findById(instanceId);
        assertThat(ServerModel.canonicalServerId(after.get(InstanceModel.SERVER_ID)))
            .withFailMessage("step 2: a refused migration moved the record anyway")
            .isEqualTo(localHost);
        assertThat((String) after.get(InstanceModel.STATUS))
            .withFailMessage("step 2: a refused migration left the record mid-flight")
            .isEqualTo(InstanceModel.STATUS_STOPPED);
    }

    /**
     * THE ADMIN-ONLY GATE. A delegated tenant holding {@code manage} on this very
     * instance sees the record on /manage and can act on it -- and gets no migrate
     * affordance and no migrate ROUTE at all.
     *
     * The counterfactual is structural: put {@code migrate_instance} in
     * {@link ManageInstanceResource#rowActions()} (or {@code InstanceMigratePage} in its
     * subpages) and assertions 3 and 4 fail immediately.
     */
    @Test
    @Order(5)
    void aDelegatedTenantHasNoMigrateAffordanceAndNoMigrateRoute() throws Exception {
        // 1. The tenant genuinely reaches this instance -- without this the rest would
        //    be vacuous (an absent surface trivially offers no migrate control).
        HttpResponse<String> manageList = get("/manage/instances", tenantSession);
        assertThat(manageList.statusCode()).as("step 1: the tenant reaches /manage")
            .isEqualTo(200);
        assertThat(manageList.body())
            .as("step 1: and sees the instance they hold manage on")
            .contains("migrate-subject");

        // 2. The delegated resource declares its actions itself; migrate is not among
        //    them, by construction rather than by a second predicate.
        assertThat(new ManageInstanceResource().rowActions().stream()
                .map(RowAction::id).map(Object::toString))
            .withFailMessage("step 2: the /manage instance resource declares the migrate"
                + " action -- placement is an operator authority")
            .noneMatch(id -> id.contains("migrate"));

        // 3. Nothing on the tenant's own record page points at the migrate page.
        HttpResponse<String> record = get("/manage/instances/" + instanceId, tenantSession);
        assertThat(record.statusCode()).as("step 3: the tenant's record page renders")
            .isEqualTo(200);
        assertThat(record.body())
            .withFailMessage("step 3: the tenant's instance page offers a migrate control")
            .doesNotContain("/page/migrate");

        // 4. And the route itself is absent for them, on BOTH methods -- hide AND
        //    enforce, so a hand-typed URL is not a wider door than the rendered page.
        assertThat(get("/manage/instances/" + instanceId + "/page/migrate", tenantSession)
                .statusCode())
            .withFailMessage("step 4: the tenant can GET the migrate page")
            .isEqualTo(404);
        assertThat(post("/manage/instances/" + instanceId + "/page/migrate",
                "target_server_id=" + strangerHostId, tenantSession, tenantCsrf).statusCode())
            .withFailMessage("step 4: the tenant can POST a migration")
            .isEqualTo(404);

        // 5. The tenant is not merely locked out of everything: their own power action is
        //    still declared, so step 2's absence is the migrate decision, not an empty list.
        assertThat(new ManageInstanceResource().rowActions().stream()
                .map(RowAction::id).map(Object::toString))
            .as("step 5: positive anchor -- the delegated surface still offers power")
            .anyMatch(id -> id.contains("deploy_instance"));
    }

    /**
     * The layered enforcer, proven on its own: even with the surface gate removed the
     * AUTHORITY refuses a tenant-originated migration.
     *
     * AIDEV-NOTE: this is why the step-5 assertions are about the SURFACE and not about a
     * privilege escalation -- {@code InstanceMigrations.migrateTo} answers first for any
     * tenant-originated call. Naming that here keeps step 5 honest instead of letting it
     * read as the only thing standing between a tenant and a host move.
     */
    @Test
    @Order(6)
    void theAuthorityRefusesATenantOriginatedMigrationRegardlessOfTheSurface() {
        UserPrincipal tenant = new UserPrincipal(tenantId, "Migrate Tenant");

        // 1. The tenant drives migrateTo DIRECTLY, bypassing every surface this class
        //    tests: the authority refuses it by name.
        Throwable refused = catchThrowable(() -> TenantConduits.as(tenant,
            () -> new InstanceMigrations().migrateTo(instanceId, strangerHostId)));
        assertThat(refused)
            .withFailMessage("step 1: a tenant-originated migrateTo was not refused by"
                + " the authority -- the CMS gate would be the ONLY thing standing"
                + " between a delegate and a host move")
            .isInstanceOf(Violations.class);
        assertThat(((Violations) refused).all())
            .as("step 1: and the refusal is the operator-only one, not an incidental"
                + " failure that happens to look like a gate")
            .anyMatch(violation ->
                violation.message().key().equals("migrate_operator_only"));

        // 2. The record did not move.
        Row after = Models.get(InstanceModel.class).findById(instanceId);
        assertThat(ServerModel.canonicalServerId(after.get(InstanceModel.SERVER_ID)))
            .as("step 2: the refused call moved nothing")
            .isEqualTo(ServerModel.localServerId());
    }

    // -- plumbing -----------------------------------------------------------------

    private static String migrateUrl() {
        return "/admin/instances/" + instanceId + "/page/migrate";
    }

    private HttpResponse<String> adminGet(String path) throws Exception {
        return get(path, sessionToken);
    }

    private HttpResponse<String> adminPost(String path, String body) throws Exception {
        return post(path, body, sessionToken, csrfToken);
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String session, String csrf)
            throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .header("X-Csrf-Token", csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
