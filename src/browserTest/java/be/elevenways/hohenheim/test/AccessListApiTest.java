package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.GrantService;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The access-list write lane of the PaaS API is the panels' own pipeline reached without a
 * browser: a list and its rule tree land through the resource forms, a basic-auth password
 * is argon2-hashed at rest and has no representation afterwards, a stranger key is refused
 * by name, and the doors are exactly the panels' (an operator writes the admin form, a
 * tenant the delegated one and owns what it authored).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccessListApiTest extends HohenheimTestBase {

    private static final String PREFIX = "access-list-api-";
    private static final String PLAINTEXT = "hunter2-in-the-clear";

    /** The permission the /manage panel demands before its access-list form is reachable. */
    private static final String MANAGE_ACCESS = "hohenheim.manage.access";

    private static String keyAdmin;
    private static String keyTenant;
    private static String keyNarrow;

    /** Filled by the create journey, consumed by the later ones. */
    private static Integer adminListId;
    private static Integer groupRuleId;
    private static Integer tenantListId;

    @BeforeAll
    static void seed() {
        int tenantId = user("access-list-api-tenant@surface.test", "Access List Tenant");
        // The tenant's own door: the /manage panel permission plus a key whose scopes
        // cover both it and the access-list capability vocabulary.
        GrantService.createDirectGrant(GrantSubjectType.USER, tenantId, MANAGE_ACCESS, true);

        int adminId = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        keyAdmin = ApiKeyService.create(adminId, PREFIX + "admin", List.of("hohenheim.*"), null)
            .plaintext();
        keyTenant = ApiKeyService.create(tenantId, PREFIX + "tenant",
            List.of(MANAGE_ACCESS,
                CapabilityScopes.format(AccessListModel.MODEL_ID, HohenheimAccess.MANAGE)), null)
            .plaintext();
        // The admin's OWN key narrowed to an unrelated vocabulary: no hohenheim permission
        // survives the narrowing, so every door here must be shut for it.
        keyNarrow = ApiKeyService.create(adminId, PREFIX + "narrow", List.of("shortlink.*"), null)
            .plaintext();
    }

    @AfterAll
    static void cleanUp() {
        AccessListModel lists = Models.get(AccessListModel.class);
        for (Row list : lists.find().where(AccessListModel.NAME.startsWith(PREFIX)).all()) {
            // The rule rows cascade off the model's own remove hook.
            lists.delete(list.get(AccessListModel.ID));
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

    private static List<Row> rulesOf(int listId) {
        return Models.get(AccessRuleModel.class).findForAccessList(listId);
    }

    private static Row ruleById(int ruleId) {
        return Models.get(AccessRuleModel.class).findById(ruleId);
    }

    // -- the journeys ----------------------------------------------------------

    /** A list and every rule kind an old installation carries land through the form pipeline. */
    @Test
    @Order(1)
    void anOperatorAuthorsAListAndItsRuleTree() throws Exception {
        // 1. The list itself: the operator form, so `shared` is settable here.
        HttpResponse<String> created = keyPost(keyAdmin, "/api/v1/access-lists", form(
            "name", PREFIX + "staff", "satisfy", "all", "shared", "true"));
        assertThat(created.statusCode()).as("step 1: the list is created: " + created.body())
            .isEqualTo(200);
        adminListId = idOf(created.body());
        Row list = Models.get(AccessListModel.class).findById(adminListId);
        assertThat((Object) list.get(AccessListModel.SATISFY))
            .as("step 1: satisfy was coerced against the enum").isEqualTo("all");
        assertThat(list.get(AccessListModel.SHARED))
            .as("step 1: an operator may publish a list installation-wide").isEqualTo(true);

        // 2. A group node: born switched ON (an empty group passes), with its own
        //    any/all mode coerced against the group sub-schema.
        HttpResponse<String> group = keyPost(keyAdmin,
            "/api/v1/access-lists/" + adminListId + "/rules",
            form("type", AccessRuleModel.TYPE_GROUP, "data.satisfy", "any"));
        assertThat(group.statusCode()).as("step 2: the group lands: " + group.body())
            .isEqualTo(200);
        groupRuleId = idOf(group.body());
        assertThat(ruleById(groupRuleId).get(AccessRuleModel.ENABLED))
            .as("step 2: a group is born switched on").isEqualTo(true);

        // 3. The credential leaf INSIDE that group: the plaintext is typed once and is an
        //    argon2 hash at rest, which only the resource pipeline does.
        HttpResponse<String> credential = keyPost(keyAdmin,
            "/api/v1/access-lists/" + adminListId + "/rules",
            form("type", AccessRuleModel.TYPE_BASIC_AUTH, "parent_id", String.valueOf(groupRuleId),
                "data.username", "earl", "data.password", PLAINTEXT, "enabled", "true"));
        assertThat(credential.statusCode())
            .as("step 3: the credential rule lands: " + credential.body()).isEqualTo(200);
        int credentialId = idOf(credential.body());
        Row stored = ruleById(credentialId);
        Map<String, Object> data = AccessRuleModel.dataOf(stored);
        assertThat(String.valueOf(data.get(AccessRuleModel.BASIC_AUTH_PASSWORD.getName())))
            .as("step 3: stored as an argon2 hash, never the plaintext")
            .startsWith("$argon2").doesNotContain(PLAINTEXT);
        assertThat(credential.body())
            .as("step 3: and the answer echoes neither the plaintext nor the hash")
            .doesNotContain(PLAINTEXT).doesNotContain("$argon2");
        assertThat(credential.body()).as("step 3: it says only that one is stored")
            .contains("\"has_password\"").contains("earl");
        assertThat((Object) stored.get(AccessRuleModel.PARENT_ID))
            .as("step 3: parented onto the group named in the body").isEqualTo(groupRuleId);
        assertThat(stored.get(AccessRuleModel.ENABLED))
            .as("step 3: a complete leaf may be switched on in the same call").isEqualTo(true);

        // 4. An address leaf at the root, and the detail read answering with the tree.
        HttpResponse<String> allow = keyPost(keyAdmin,
            "/api/v1/access-lists/" + adminListId + "/rules",
            form("type", AccessRuleModel.TYPE_IP_ALLOW, "data.network", "10.0.0.0/8",
                "enabled", "true"));
        assertThat(allow.statusCode()).as("step 4: the network rule lands: " + allow.body())
            .isEqualTo(200);
        assertThat(rulesOf(adminListId)).as("step 4: three nodes in the tree").hasSize(3);
        HttpResponse<String> detail = keyGet(keyAdmin, "/api/v1/access-lists/" + adminListId);
        // (the network is matched without its prefix slash: the JSON writer escapes "/")
        assertThat(detail.body()).as("step 4: the detail read carries the rules")
            .contains("\"rules\"").contains("10.0.0.0").contains(AccessRuleModel.TYPE_BASIC_AUTH);

        // 5. The refusals are the form's, by name: a stranger key at either level, and a
        //    type outside the vocabulary (which is refused BEFORE any node is born).
        HttpResponse<String> stranger = keyPost(keyAdmin, "/api/v1/access-lists",
            form("name", PREFIX + "stranger", "colour", "red"));
        assertThat(stranger.statusCode()).as("step 5: a stranger key is a typed refusal")
            .isEqualTo(422);
        assertThat(codeOf(stranger.body())).isEqualTo("zenit.coercion.unknown_field");
        assertThat(Models.get(AccessListModel.class).find()
                .where(AccessListModel.NAME.eq(PREFIX + "stranger")).first())
            .as("step 5: and wrote no row").isNull();
        HttpResponse<String> badType = keyPost(keyAdmin,
            "/api/v1/access-lists/" + adminListId + "/rules", form("type", "sudo"));
        assertThat(badType.statusCode()).isEqualTo(422);
        assertThat(codeOf(badType.body())).as("step 5: named as an unknown rule type")
            .isEqualTo("unknown_type");
        assertThat(rulesOf(adminListId)).as("step 5: no node was born for it").hasSize(3);

        // 6. A misspelled per-type setting is refused by the resolved sub-schema -- and
        //    the node it was for stays behind, switched OFF, exactly as the Rules tab
        //    leaves one when the operator abandons its form. A disabled rule enforces
        //    nothing, and the next read shows it.
        HttpResponse<String> misspelled = keyPost(keyAdmin,
            "/api/v1/access-lists/" + adminListId + "/rules",
            form("type", AccessRuleModel.TYPE_IP_DENY, "data.netwerk", "10.0.0.1"));
        assertThat(misspelled.statusCode()).isEqualTo(422);
        assertThat(codeOf(misspelled.body())).isEqualTo("zenit.coercion.unknown_field");
        List<Row> afterRefusal = rulesOf(adminListId);
        assertThat(afterRefusal).as("step 6: the unconfigured node is there").hasSize(4);
        assertThat(afterRefusal.stream()
                .filter(row -> AccessRuleModel.TYPE_IP_DENY.equals(row.get(AccessRuleModel.TYPE)))
                .allMatch(row -> Boolean.FALSE.equals(row.get(AccessRuleModel.ENABLED))))
            .as("step 6: and it is switched off, so it enforces nothing").isTrue();
    }

    /** The doors are the panels': a tenant owns what it authors and sees nothing else. */
    @Test
    @Order(2)
    void aTenantOwnsWhatItAuthorsAndSeesNothingElse() throws Exception {
        // 1. A tenant creates through the DELEGATED form, which plants its ownership.
        HttpResponse<String> created = keyPost(keyTenant, "/api/v1/access-lists",
            form("name", PREFIX + "tenant", "satisfy", "any"));
        assertThat(created.statusCode()).as("step 1: the tenant may author one: " + created.body())
            .isEqualTo(200);
        tenantListId = idOf(created.body());
        assertThat(keyGet(keyTenant, "/api/v1/access-lists/" + tenantListId).statusCode())
            .as("step 1: and manages it afterwards (the create planted the grant)")
            .isEqualTo(200);

        // 2. The operator's switch is not in the delegated form, so it is not a key a
        //    tenant may submit at all.
        HttpResponse<String> shared = keyPost(keyTenant, "/api/v1/access-lists",
            form("name", PREFIX + "tenant-shared", "shared", "true"));
        assertThat(shared.statusCode()).as("step 2: publishing is the operator's declaration")
            .isEqualTo(422);
        assertThat(codeOf(shared.body())).isEqualTo("zenit.coercion.unknown_field");

        // 3. Someone else's list is a uniform 404 on every verb, never an oracle.
        assertThat(keyGet(keyTenant, "/api/v1/access-lists/" + adminListId).statusCode())
            .as("step 3: a foreign list does not read").isEqualTo(404);
        assertThat(keyPost(keyTenant, "/api/v1/access-lists/" + adminListId + "/rules",
            form("type", AccessRuleModel.TYPE_IP_ALLOW)).statusCode())
            .as("step 3: nor takes a rule").isEqualTo(404);
        assertThat(keyPost(keyTenant, "/api/v1/access-lists/" + adminListId + "/delete", "")
            .statusCode()).as("step 3: nor deletes").isEqualTo(404);
        HttpResponse<String> listed = keyGet(keyTenant, "/api/v1/access-lists");
        assertThat(listed.body()).as("step 3: the listing is exactly its own")
            .contains(PREFIX + "tenant").doesNotContain(PREFIX + "staff");
        assertThat(rulesOf(adminListId)).as("step 3: the operator's tree is untouched")
            .hasSize(4);

        // 4. A key narrowed away from every hohenheim permission opens no door.
        assertThat(keyPost(keyNarrow, "/api/v1/access-lists",
            form("name", PREFIX + "nope")).statusCode())
            .as("step 4: the create needs the panel permission the key no longer covers")
            .isEqualTo(403);
        assertThat(keyGet(keyNarrow, "/api/v1/access-lists/" + adminListId).statusCode())
            .as("step 4: and the walk answers nothing for it either").isEqualTo(404);
        assertThat(Models.get(AccessListModel.class).find()
                .where(AccessListModel.NAME.eq(PREFIX + "nope")).first())
            .as("step 4: nothing was created").isNull();
    }

    /** Deleting a list takes its whole rule tree with it, and only once. */
    @Test
    @Order(3)
    void deletingAListTakesItsRulesWithIt() throws Exception {
        HttpResponse<String> deleted = keyPost(keyAdmin,
            "/api/v1/access-lists/" + adminListId + "/delete", "");
        assertThat(deleted.statusCode()).as("step 1: the list is deleted: " + deleted.body())
            .isEqualTo(200);
        assertThat(Models.get(AccessListModel.class).findById(adminListId))
            .as("step 1: the row is gone").isNull();
        assertThat(rulesOf(adminListId)).as("step 1: and its rules went with it").isEmpty();
        assertThat(keyGet(keyAdmin, "/api/v1/access-lists/" + adminListId).statusCode())
            .as("step 1: it reads as absent").isEqualTo(404);
        assertThat(keyPost(keyAdmin, "/api/v1/access-lists/" + adminListId + "/delete", "")
            .statusCode()).as("step 1: and cannot be deleted twice").isEqualTo(404);
        assertThat(keyGet(keyTenant, "/api/v1/access-lists/" + tenantListId).statusCode())
            .as("step 1: the tenant's list is untouched").isEqualTo(200);
    }
}
