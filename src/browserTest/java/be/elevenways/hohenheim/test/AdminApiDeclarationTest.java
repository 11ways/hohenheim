package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.routing.Endpoint;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin-only automation endpoints DECLARE the admin permission, so the authorization
 * middleware refuses a tenant key before any handler runs; the handler's own admin check
 * stays as the second line. The instance deploy verbs state their login requirement instead
 * of leaning on the "/" baseline.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class AdminApiDeclarationTest extends HohenheimTestBase {

    /** Every automation endpoint whose only UI is the operator panel. */
    private static final List<Endpoint<?>> ADMIN_ONLY = List.of(
        HohenheimEndpoints.API_V1_HOSTS, HohenheimEndpoints.API_V1_HOST,
        HohenheimEndpoints.API_DATABASE_ENGINES, HohenheimEndpoints.API_DATABASE_ENGINE,
        HohenheimEndpoints.API_DATABASE_MOVE_SHARED,
        HohenheimEndpoints.API_V1_DNS_ZONES, HohenheimEndpoints.API_V1_DNS_ZONE_CREATE,
        HohenheimEndpoints.API_V1_DNS_ZONE_IMPORT,
        HohenheimEndpoints.API_V1_SITE_CREATE, HohenheimEndpoints.API_V1_SITE_DELETE);

    @Test
    void adminOnlyVerbsAreDeclaredSoAndRefusedAtTheDoor() throws Exception {
        // 1. The declarations themselves: the admin permission on every operator-only verb.
        for (Endpoint<?> endpoint : ADMIN_ONLY) {
            assertThat(endpoint.getRequiredPermissions())
                .as("step 1: %s declares the admin permission", endpoint.getId())
                .contains(HohenheimSources.ADMIN_ACCESS);
        }

        // 2. The deploy-tab verbs state their own login requirement.
        assertThat(HohenheimEndpoints.INSTANCES_DEPLOY.isLoginRequired())
            .as("step 2: the deploy form declares login").isTrue();
        assertThat(HohenheimEndpoints.INSTANCES_ROLLBACK.isLoginRequired())
            .as("step 2: the rollback form declares login").isTrue();

        // 3. On the wire: a key whose owner holds no admin permission is refused at the
        //    door for a host read, a zone list and a site create alike.
        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "admin-api-declaration@surface.test");
        tenant.set(UserModel.DISPLAY_NAME, "Admin Api Declaration");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Now.instant());
        tenant.set(UserModel.UPDATED_AT, Now.instant());
        AuthModels.users().save(tenant);
        String key = ApiKeyService.create(tenant.get(UserModel.ID), "admin-api-declaration",
            List.of("hohenheim.*"), null).plaintext();
        HttpResponse<String> hosts = keyGet(key, "/api/v1/hosts");
        assertThat(hosts.statusCode()).as("step 3: a tenant key cannot read hosts").isEqualTo(403);
        HttpResponse<String> zones = keyGet(key, "/api/v1/dns/zones");
        assertThat(zones.statusCode()).as("step 3: nor list zones").isEqualTo(403);
        HttpResponse<String> create = keyPost(key, "/api/v1/sites", "name=admin-api-declaration");
        assertThat(create.statusCode()).as("step 3: nor create a site").isEqualTo(403);
    }
}
