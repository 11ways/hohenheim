package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance write lane of the PaaS API is the admin create form's own pipeline reached
 * without a browser: a workload lands in {@code created} with its per-kind settings coerced,
 * a stranger key is refused by name, a body naming a template still rides the tenant's
 * template funnel, and the doors are the panels' (create admin-only, destroy behind the
 * {@code destroy} capability the teardown service itself demands).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceApiTest extends HohenheimTestBase {

    private static final String PREFIX = "instance-api-";

    private static String keyAdmin;
    private static String keyTenant;
    private static String keyNarrow;

    /** A pre-existing workload the tenant may SEE and must not be able to destroy. */
    private static Integer viewOnlyId;

    /** Filled by the create journey, consumed by the delete journey. */
    private static Integer createdId;

    /** A Docker host the application kind's picker rules accept. */
    private static Integer hostId;

    @BeforeAll
    static void seed() {
        hostId = host(PREFIX + "docker-host");
        int tenantId = user("instance-api-tenant@surface.test", "Instance Api Tenant");
        viewOnlyId = instance(PREFIX + "view-only");
        // VIEW and nothing else: the capability the read lane asks, deliberately not the
        // one a destroy asks.
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, viewOnlyId,
            HohenheimAccess.VIEW, true);

        int adminId = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        keyAdmin = ApiKeyService.create(adminId, PREFIX + "admin", List.of("hohenheim.*"), null)
            .plaintext();
        keyTenant = ApiKeyService.create(tenantId, PREFIX + "tenant",
            List.of(CapabilityScopes.format(InstanceModel.MODEL_ID, HohenheimAccess.VIEW)), null)
            .plaintext();
        // The admin's OWN key narrowed to an unrelated vocabulary: no admin permission
        // survives the narrowing, so the create door must be shut for it.
        keyNarrow = ApiKeyService.create(adminId, PREFIX + "narrow", List.of("shortlink.*"), null)
            .plaintext();
    }

    @AfterAll
    static void cleanUp() {
        InstanceModel instances = Models.get(InstanceModel.class);
        for (Row row : instances.find().where(InstanceModel.NAME.startsWith(PREFIX)).all()) {
            instances.delete(row.get(InstanceModel.ID));
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

    /**
     * A host the create form's own picker rules would offer for a Docker-runtime kind:
     * the API is held to the SAME narrowing, so a host without these facts is refused
     * `relation_out_of_scope` exactly as the select would never have listed it.
     */
    private static int host(String name) {
        var servers = Models.get(ServerModel.class);
        Row existing = servers.find().where(ServerModel.NAME.eq(name)).first();
        if (existing != null) {
            return existing.get(ServerModel.ID);
        }
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.VOLUME_BACKEND, "btrfs");
        row.set(ServerModel.SSH_TARGET, "root@" + name + ".test");
        servers.save(row);
        return row.get(ServerModel.ID);
    }

    private static int instance(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(
            Map.of("image", "alpine", "tag", "latest")));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static String form(String... pairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    private static int idOf(String json) {
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        assertThat(matcher.find()).as("the response carries an id: " + json).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static String codeOf(String json) {
        Matcher matcher = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("the refusal carries a code: " + json).isTrue();
        return matcher.group(1);
    }

    // -- the journeys ----------------------------------------------------------

    /** A workload lands through the admin form's pipeline, in the state a create leaves. */
    @Test
    @Order(1)
    void aWorkloadLandsThroughTheCreateFormsOwnPipeline() throws Exception {
        // 1. A release-managed application with its git source: name, kind, host and the
        //    kind's own settings schema, exactly the create form's entries.
        HttpResponse<String> created = keyPost(keyAdmin, "/api/v1/instances", form(
            "name", PREFIX + "earl", "kind", "hohenheim:application",
            "server_id", String.valueOf(hostId),
            "settings.repository_url", "https://example.test/earl.git",
            "settings.branch", "main", "settings.container_port", "3000",
            "settings.console_kind", "tty"));
        assertThat(created.statusCode()).as("step 1: the instance is created: " + created.body())
            .isEqualTo(200);
        createdId = idOf(created.body());
        Row row = Models.get(InstanceModel.class).findById(createdId);
        assertThat((Object) row.get(InstanceModel.KIND))
            .as("step 1: the kind was coerced against the authorable registry")
            .isEqualTo("hohenheim:application");
        assertThat((Object) row.get(InstanceModel.STATUS))
            .as("step 1: a create deploys nothing -- the row lands in `created`")
            .isEqualTo(InstanceModel.STATUS_CREATED);
        assertThat(String.valueOf(row.get(InstanceModel.SETTINGS)))
            .as("step 1: the settings were coerced against the kind's own schema")
            .contains("branch=main").contains("container_port=3000")
            .contains("console_kind=tty");
        assertThat((Object) row.get(InstanceModel.SERVER_ID))
            .as("step 1: an operator's explicit host is honoured verbatim").isEqualTo(hostId);
        assertThat((Object) row.get(InstanceModel.TEMPLATE_ID))
            .as("step 1: nothing links it to a template -- that is the other lane").isNull();

        // 2. The read lane answers with the same record.
        HttpResponse<String> detail = keyGet(keyAdmin, "/api/v1/instances/" + createdId);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).as("step 2: the projection names it")
            .contains(PREFIX + "earl").contains(InstanceModel.STATUS_CREATED);

        // 3. A stranger key at either level is a typed refusal, and no row is written.
        HttpResponse<String> stranger = keyPost(keyAdmin, "/api/v1/instances", form(
            "name", PREFIX + "stranger", "kind", "hohenheim:application", "colour", "red"));
        assertThat(stranger.statusCode()).isEqualTo(422);
        assertThat(codeOf(stranger.body())).isEqualTo("zenit.coercion.unknown_field");
        HttpResponse<String> misspelled = keyPost(keyAdmin, "/api/v1/instances", form(
            "name", PREFIX + "stranger", "kind", "hohenheim:application",
            "settings.branhc", "main"));
        assertThat(misspelled.statusCode()).as("step 3: a misspelled setting too")
            .isEqualTo(422);
        assertThat(codeOf(misspelled.body())).isEqualTo("zenit.coercion.unknown_field");
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "stranger")).first())
            .as("step 3: neither refused create wrote a row").isNull();

        // 4. The fork is the PRESENCE of template_id, never whether it resolves: a body
        //    naming a template still rides the tenant's approved-template funnel and is
        //    refused by IT, rather than silently falling into the operator lane.
        HttpResponse<String> template = keyPost(keyAdmin, "/api/v1/instances", form(
            "name", PREFIX + "templated", "template_id", "987654"));
        assertThat(template.statusCode()).isEqualTo(422);
        assertThat(codeOf(template.body())).as("step 4: refused as an unknown template")
            .isEqualTo("unknown_template");
    }

    /** The doors are the panels': create is admin-only, destroy asks the record. */
    @Test
    @Order(2)
    void theDoorsAreExactlyThePanelsDoors() throws Exception {
        // 1. No tenant create lane exists at all (ManageInstanceResource is not creatable),
        //    and a key narrowed away from the admin permission is refused the same way.
        assertThat(keyPost(keyTenant, "/api/v1/instances", form(
            "name", PREFIX + "nope", "kind", "hohenheim:application")).statusCode())
            .as("step 1: a tenant cannot author a workload from settings")
            .isEqualTo(403);
        assertThat(keyPost(keyNarrow, "/api/v1/instances", form(
            "name", PREFIX + "nope", "kind", "hohenheim:application")).statusCode())
            .as("step 1: nor a narrowed admin key").isEqualTo(403);
        assertThat(Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.eq(PREFIX + "nope")).first())
            .as("step 1: nothing was created").isNull();

        // 2. Seeing a workload is not destroying it: the tenant holds `view`, and the
        //    teardown funnel demands `destroy` INSIDE InstanceService -- so the refusal
        //    is the service's typed one, the same the row action gets, never a wider door
        //    opened by the delete route existing.
        assertThat(keyGet(keyTenant, "/api/v1/instances/" + viewOnlyId).statusCode())
            .as("step 2: `view` reads").isEqualTo(200);
        HttpResponse<String> refused = keyPost(keyTenant,
            "/api/v1/instances/" + viewOnlyId + "/delete", "");
        assertThat(refused.statusCode()).as("step 2: and destroys nothing: " + refused.body())
            .isEqualTo(422);
        assertThat(codeOf(refused.body()))
            .as("step 2: named by the service gate, which never says WHICH capability")
            .isEqualTo("instance_not_permitted");
        assertThat((Object) Models.get(InstanceModel.class).findById(viewOnlyId)
                .get(InstanceModel.DELETED_AT))
            .as("step 2: the record is untouched").isNull();

        // 3. A workload the tenant holds nothing on is the uniform 404, never an oracle.
        assertThat(keyGet(keyTenant, "/api/v1/instances/" + createdId).statusCode())
            .as("step 3: a foreign workload does not read").isEqualTo(404);
        assertThat(keyPost(keyTenant, "/api/v1/instances/" + createdId + "/delete", "")
            .statusCode()).as("step 3: nor delete").isEqualTo(404);
    }

    /** Deleting a workload is the form's own destroy: torn down, trashed, gone from the API. */
    @Test
    @Order(3)
    void deletingAWorkloadTrashesIt() throws Exception {
        HttpResponse<String> deleted = keyPost(keyAdmin,
            "/api/v1/instances/" + createdId + "/delete", "");
        assertThat(deleted.statusCode()).as("step 1: the instance is deleted: " + deleted.body())
            .isEqualTo(200);
        assertThat((Object) Models.get(InstanceModel.class).findById(createdId)
                .get(InstanceModel.DELETED_AT))
            .as("step 1: soft-deleted, exactly like the form's destroy").isNotNull();
        assertThat(keyGet(keyAdmin, "/api/v1/instances/" + createdId).statusCode())
            .as("step 1: a trashed workload reads as absent").isEqualTo(404);
        assertThat(keyPost(keyAdmin, "/api/v1/instances/" + createdId + "/delete", "")
            .statusCode()).as("step 1: and cannot be deleted twice").isEqualTo(404);
        assertThat((Object) Models.get(InstanceModel.class).findById(viewOnlyId)
                .get(InstanceModel.DELETED_AT))
            .as("step 1: the other workload is untouched").isNull();
    }
}
