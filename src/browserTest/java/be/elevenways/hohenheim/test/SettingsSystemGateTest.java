package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.zenit.auth.AuthEndpoints;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.security.Permissions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The installation settings, which feed fetches Hohenheim makes with operator trust (the {@code auth_proteus}
 * endpoint rides an any-address guard), are editable under the non-delegable {@code hohenheim.admin.system} alone:
 * a delegated admin reaches the panel but never the settings.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class SettingsSystemGateTest extends HohenheimTestBase {

    private static TestSession delegatedAdmin;

    @BeforeAll
    static void seedDelegatedAdmin() {
        Integer userId = ApiSupport.user("delegated-admin@hohenheim.local", "Delegated Admin");
        // zenit-auth's own /admin prefix baseline demands auth.admin.access on top of the panel grant.
        GrantService.createDirectGrant(GrantSubjectType.USER, userId, AuthEndpoints.PERM_ADMIN_ACCESS.value(), true);
        GrantService.createDirectGrant(GrantSubjectType.USER, userId, HohenheimSources.ADMIN_ACCESS.value(), true);
        delegatedAdmin = sessionFor(userId);
    }

    @Test
    void aDelegatedAdminReachesThePanelButNeverTheOperatorTrustedSettings() throws Exception {
        // 1. The permission is declared and not delegable.
        assertThat(Permissions.get(HohenheimSources.ADMIN_SYSTEM.value()))
            .as("step 1: hohenheim.admin.system is declared").isNotNull();
        assertThat(Permissions.isDelegable(HohenheimSources.ADMIN_SYSTEM.value())).as("step 1: and never delegable")
            .isFalse();
        assertThat(Permissions.isDelegable(HohenheimSources.ADMIN_ACCESS.value()))
            .as("step 1: unlike the panel grant the delegated admin holds").isTrue();

        // 2. The delegated admin enters the panel.
        // The panel root redirects to its landing page; admission is a page or a redirect that stays in the panel.
        HttpResponse<String> panel = httpGet("/admin", delegatedAdmin.token());
        assertThat(panel.statusCode()).as("step 2: the delegable panel grant opens the panel").isIn(200, 302, 303);
        if (panel.statusCode() != 200) {
            assertThat(panel.headers().firstValue("Location"))
                .as("step 2: into the panel, never to sign-in")
                .hasValueSatisfying(location -> assertThat(location).startsWith("/admin").doesNotContain("login"));
        }

        // 3. The settings page, read or written, refuses them, and the operator-trusted endpoint does not move.
        String endpointBefore = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ENDPOINT);
        HttpResponse<String> read = httpGet("/admin/settings", delegatedAdmin.token());
        assertThat(read.statusCode()).as("step 3: the settings page refuses a delegated admin (HTTP %s)",
            read.statusCode()).isEqualTo(403);
        HttpResponse<String> write = httpPostForm("/admin/settings",
            "auth_proteus.endpoint=http%3A%2F%2F127.0.0.1%3A8080%2F", delegatedAdmin.token(), delegatedAdmin.csrf());
        assertThat(write.statusCode()).as("step 3: and so does a write (HTTP %s)", write.statusCode()).isEqualTo(403);
        assertThat((Object) Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ENDPOINT))
            .as("step 3: the endpoint is unchanged").isEqualTo(endpointBefore);

        // 4. The operator, who holds "*", still edits the settings.
        assertThat(adminGet("/admin/settings").statusCode()).as("step 4: the operator opens the settings")
            .isEqualTo(200);
    }
}
