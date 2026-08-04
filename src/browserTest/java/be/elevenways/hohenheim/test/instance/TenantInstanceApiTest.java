package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.PermissionChecker;
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
 * The tenant instance API: proves it is the SAME door as /manage rather than a wider
 * one. Every refusal asserted here is the named violation the HTML surface renders for
 * the same act, and every visibility answer is byte-compared against the answer for an
 * id that does not exist.
 *
 * No daemon is needed: the paths exercised refuse before any driver call.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantInstanceApiTest extends HohenheimTestBase {

    private static final String PREFIX = "tenant-api-";

    private static Integer tenantAId;
    private static Integer tenantBId;
    private static Integer instanceAId;
    private static Integer instanceBId;
    private static Integer unapprovedTemplateId;

    private static String keyManageA;
    private static String keyCreateA;
    private static String keyManageB;
    private static String keySnapshotsA;
    private static String sessionA;

    @BeforeAll
    static void seed() {
        tenantAId = tenant("api-tenant-a@surface.test", "Api Tenant A");
        tenantBId = tenant("api-tenant-b@surface.test", "Api Tenant B");

        instanceAId = instance(PREFIX + "alpha");
        instanceBId = instance(PREFIX + "bravo");
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.MANAGE, true);
        RecordGrants.grant("user", tenantAId, InstanceModel.MODEL_ID, instanceAId,
            HohenheimAccess.SNAPSHOTS, true);
        RecordGrants.grant("user", tenantBId, InstanceModel.MODEL_ID, instanceBId,
            HohenheimAccess.MANAGE, true);

        String manageScope = CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.MANAGE);
        String snapshotScope = CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.SNAPSHOTS);
        keyManageA = ApiKeyService.create(tenantAId, PREFIX + "a-manage",
            List.of(manageScope), null).plaintext();
        keySnapshotsA = ApiKeyService.create(tenantAId, PREFIX + "a-snapshots",
            List.of(manageScope, snapshotScope), null).plaintext();
        keyManageB = ApiKeyService.create(tenantBId, PREFIX + "b-manage",
            List.of(manageScope), null).plaintext();
        // A key that also carries the permission-shaped create scope, so the create
        // lane is exercised against the FUNNEL rather than against key narrowing.
        keyCreateA = ApiKeyService.create(tenantAId, PREFIX + "a-create",
            List.of(manageScope, HohenheimAccess.INSTANCES_CREATE.value()), null).plaintext();

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, tenantAId.longValue());
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);
        sessionA = session.id();

        Model templates = Models.get(InstanceTemplateModel.class);
        Row template = templates.createEmptyRow();
        template.set(InstanceTemplateModel.NAME, PREFIX + "unapproved");
        template.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        template.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest")));
        templates.save(template);
        unapprovedTemplateId = template.get(InstanceTemplateModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        Model instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            instances.delete(row.get(InstanceModel.ID));
        }
        Model templates = Models.get(InstanceTemplateModel.class);
        for (Row row : templates.find().where(InstanceTemplateModel.NAME.startsWith(PREFIX)).all()) {
            templates.delete(row.get(InstanceTemplateModel.ID));
        }
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

    // -- the journeys ---------------------------------------------------------

    /** The API answers to keys only, and shows one tenant exactly its own inventory. */
    @Test
    @Order(1)
    void theApiIsKeyOnlyAndNeverListsAnotherTenantsInstance() throws Exception {
        // 1. A browser session is not an automation credential. This is what makes the
        //    csrfExempt declaration on the mutating routes safe.
        HttpResponse<String> viaCookie = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/api/v1/instances"))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionA)
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(viaCookie.statusCode())
            .as("step 1: a session cookie is refused on the automation surface")
            .isEqualTo(403);

        // 2. An anonymous call never reaches a handler. The framework's answer to an
        //    unauthenticated request on a login-required route is the login redirect,
        //    so the assertion is "not 200, and no record data in the body" rather than
        //    a particular refusal status.
        HttpResponse<String> anonymous = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build()
            .send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/v1/instances"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode())
            .as("step 2: an anonymous call is refused (401/403) or bounced to login")
            .isIn(302, 303, 401, 403);
        assertThat(anonymous.body())
            .as("step 2: and carries no instance data whatsoever")
            .doesNotContain(PREFIX + "alpha").doesNotContain(PREFIX + "bravo");

        // 3. The list is the CONTENT assertion: tenant A's name is present, tenant B's
        //    is absent from the response BODY, not merely from a status code.
        HttpResponse<String> list = keyGet(keyManageA, "/api/v1/instances");
        assertThat(list.statusCode()).as("step 3: the key's list renders").isEqualTo(200);
        assertThat(list.body()).as("step 3: tenant A's own instance is projected")
            .contains(PREFIX + "alpha");
        assertThat(list.body()).as("step 3: tenant B's instance is NOT in the response")
            .doesNotContain(PREFIX + "bravo");

        // 4. The projection is a whitelist: the image, the command and the placement
        //    host never leave, whatever columns the record grows.
        assertThat(list.body())
            .as("step 4: no image, command, host or quota material in the projection")
            .doesNotContain("alpine")
            .doesNotContain("sleep 300")
            .doesNotContain("server_id")
            .doesNotContain("quota_bucket");
    }

    /** COUNTERFACTUAL: unowned and nonexistent are one answer, status and body. */
    @Test
    @Order(2)
    void anUnownedIdAndAnAbsentIdAreIndistinguishable() throws Exception {
        int absentId = 900_000_001;
        assertThat(Models.get(InstanceModel.class).findById(absentId))
            .as("step 1: the control id really does not exist").isNull();

        HttpResponse<String> foreign = keyGet(keyManageA, "/api/v1/instances/" + instanceBId);
        HttpResponse<String> absent = keyGet(keyManageA, "/api/v1/instances/" + absentId);
        assertThat(foreign.statusCode()).as("step 2: an unowned id is 404").isEqualTo(404);
        assertThat(absent.statusCode()).as("step 2: an absent id is 404").isEqualTo(404);
        assertThat(foreign.body()).as("step 3: the bodies are identical -- no oracle")
            .isEqualTo(absent.body());

        // 4. And the mutating routes answer the same way, so a prober cannot learn
        //    existence from a POST either.
        HttpResponse<String> powerForeign = keyPost(keyManageA,
            "/api/v1/instances/" + instanceBId + "/power", "action=start");
        HttpResponse<String> powerAbsent = keyPost(keyManageA,
            "/api/v1/instances/" + absentId + "/power", "action=start");
        assertThat(powerForeign.statusCode()).as("step 4: power on an unowned id is 404")
            .isEqualTo(404);
        assertThat(powerForeign.body()).as("step 4: identical to the absent id's answer")
            .isEqualTo(powerAbsent.body());
        assertThat((String) Models.get(InstanceModel.class).findById(instanceBId)
                .get(InstanceModel.STATUS))
            .as("step 4: and tenant B's instance was not touched")
            .isEqualTo(InstanceModel.STATUS_CREATED);

        // 5. Tenant B's key cannot reach tenant A's instance either: a key never
        //    exceeds what its OWNER holds.
        assertThat(keyGet(keyManageB, "/api/v1/instances/" + instanceAId).statusCode())
            .as("step 5: the other tenant's key is equally blind").isEqualTo(404);
    }

    /** COUNTERFACTUAL: the API refuses exactly what the HTML surface refuses. */
    @Test
    @Order(3)
    void theApiRefusesWithTheSameNamedViolationAsTheHtmlSurface() throws Exception {
        // 1. Ask the SAME act through the in-process funnel the panel's row action uses,
        //    and keep the violation key it refuses with. Whatever that key is on this
        //    installation, the API must answer with it -- comparing against the LIVE
        //    answer instead of a hardcoded one is what makes this an identity proof
        //    rather than two independent expectations that could drift apart.
        UserPrincipal principalA = new UserPrincipal(tenantAId, "Api Tenant A");
        Throwable direct = catchThrowable(() ->
            TenantConduits.as(principalA, () -> new InstanceService().deploy(instanceAId)));
        String panelKey = firstViolationKey(direct);
        assertThat(panelKey)
            .as("step 1: the panel refusal is an operational one, not the capability gate")
            .isNotEqualTo("instance_not_permitted");

        HttpResponse<String> power = keyPost(keyManageA,
            "/api/v1/instances/" + instanceAId + "/power", "action=start");
        assertThat(power.statusCode()).as("step 2: a typed refusal is 422, never 500")
            .isEqualTo(422);
        assertThat(power.body())
            .as("step 2: and carries the EXACT violation the panel refused with: " + panelKey)
            .contains(panelKey);

        // 3. An unknown verb is a named refusal too, and changes nothing.
        HttpResponse<String> bogus = keyPost(keyManageA,
            "/api/v1/instances/" + instanceAId + "/power", "action=selfdestruct");
        assertThat(bogus.statusCode()).as("step 3: an unknown power verb is 422").isEqualTo(422);
        assertThat(bogus.body()).as("step 3: named").contains("unknown_power_action");

        // 4. CREATE authority: the API funnel asks for hohenheim.instances.create first,
        //    exactly as the create page does, and a key whose owner lacks it is refused
        //    by name with nothing persisted.
        HttpResponse<String> unauthorized = keyPost(keyCreateA, "/api/v1/instances",
            "template_id=" + unapprovedTemplateId + "&name=" + PREFIX + "api-created");
        assertThat(unauthorized.statusCode()).as("step 4: refused, typed").isEqualTo(422);
        assertThat(unauthorized.body())
            .as("step 4: with the create-authority violation the HTML surface uses")
            .contains("instance_create_not_permitted");

        // 5. Grant the owner the permission; the SAME call now gets past authority and
        //    meets the template approval gate -- the second named refusal the HTML
        //    surface produces for the same submit.
        GrantService.createDirectGrant("user", tenantAId,
            HohenheimAccess.INSTANCES_CREATE.value(), true);
        HttpResponse<String> unapproved = keyPost(keyCreateA, "/api/v1/instances",
            "template_id=" + unapprovedTemplateId + "&name=" + PREFIX + "api-created");
        assertThat(unapproved.statusCode()).as("step 5: still refused, typed").isEqualTo(422);
        assertThat(unapproved.body())
            .as("step 5: now by the approval gate, named")
            .contains("template_not_approved");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "api-created")).count())
            .as("step 5: and nothing was created along the way").isZero();

        // 6. The KEY narrows the permission lane too: a key without the create scope
        //    cannot create even though its owner now may.
        HttpResponse<String> narrowKey = keyPost(keyManageA, "/api/v1/instances",
            "template_id=" + unapprovedTemplateId + "&name=" + PREFIX + "api-created-3");
        assertThat(narrowKey.body())
            .as("step 6: a capability-only key carries no create permission")
            .contains("instance_create_not_permitted");
    }

    /** @return the first violation key of a typed refusal */
    private static String firstViolationKey(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        var all = ((Violations) thrown).all();
        assertThat(all).as("the refusal carries at least one violation").isNotEmpty();
        return all.get(0).message().key();
    }

    /** COUNTERFACTUAL: a key is narrowed by its scopes, both ways. */
    @Test
    @Order(4)
    void aKeyCannotExceedItsScopesNorItsOwnersAuthority() throws Exception {
        // 1. The owner holds `snapshots` on this instance, but the manage-only KEY does
        //    not carry the scope: the credential layer narrows it away.
        HttpResponse<String> narrow = keyPost(keyManageA,
            "/api/v1/instances/" + instanceAId + "/snapshot", "");
        assertThat(narrow.statusCode()).as("step 1: refused, typed").isEqualTo(422);
        assertThat(narrow.body())
            .as("step 1: a key scoped to manage cannot snapshot, even though its owner may")
            .contains("instance_not_permitted");

        // 2. The SAME owner, the same instance, a key that DOES carry the scope: the
        //    capability gate is passed and the refusal moves on to the driver. Without
        //    this half, step 1 would prove only that snapshots never work.
        HttpResponse<String> scoped = keyPost(keySnapshotsA,
            "/api/v1/instances/" + instanceAId + "/snapshot", "");
        assertThat(scoped.body())
            .as("step 2: the correctly scoped key passes the capability gate")
            .doesNotContain("instance_not_permitted");

        // 3. And a key can never be MINTED beyond what its owner holds: tenant B holds
        //    no snapshots grant anywhere, so the interactive mint path refuses.
        AccessContext actorB = AccessContext.of(stubConduit(new UserPrincipal(tenantBId, "Api Tenant B")),
            new UserPrincipal(tenantBId, "Api Tenant B"), PermissionChecker.DENY_ALL);
        Throwable refusedMint = catchThrowable(() -> ApiKeyService.create(actorB, tenantBId,
            PREFIX + "b-snapshots",
            List.of(CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.SNAPSHOTS)),
            null));
        assertThat(refusedMint)
            .as("step 3: minting a capability its owner does not hold is refused")
            .isInstanceOf(IllegalArgumentException.class);

        // 4. image_any is NOT delegable at all: not even a holder could mint it.
        Throwable refusedImageAny = catchThrowable(() -> ApiKeyService.create(actorB, tenantBId,
            PREFIX + "b-image-any",
            List.of(CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.IMAGE_ANY)),
            null));
        assertThat(refusedImageAny)
            .as("step 4: a non-delegable capability can never enter a key scope")
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** A conduit carrying nothing but an identity, for the interactive mint path. */
    private static be.elevenways.zenit.common.conduit.Conduit stubConduit(UserPrincipal principal) {
        return be.elevenways.zenit.common.conduit.Conduit.class.cast(
            java.lang.reflect.Proxy.newProxyInstance(
                TenantInstanceApiTest.class.getClassLoader(),
                new Class<?>[] { be.elevenways.zenit.common.conduit.Conduit.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> null;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TenantInstanceApiTest.stub";
                    default -> defaultValue(method.getReturnType());
                }));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
