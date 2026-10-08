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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The installation settings page is an ordinary admin peer, while its operator-trusted keys carry their own authority:
 * the {@code auth_proteus} endpoint, fetched with an any-address guard, is host-only for everyone, the rest of the
 * Proteus login needs the non-delegable {@code hohenheim.admin.system}, and so do the framework's settings.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class SettingsSystemGateTest extends HohenheimTestBase {

    private static final String PROXY_AUTH_SECTION = "/admin/settings?section=setting-proxy-auth_proteus";

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
    void aDelegatedAdminOpensTheSettingsButNeverChangesTheOperatorTrustedKeys() throws Exception {
        // 1. The system tier is declared and not delegable.
        assertThat(Permissions.get(HohenheimSources.ADMIN_SYSTEM.value()))
            .as("step 1: hohenheim.admin.system is declared").isNotNull();
        assertThat(Permissions.isDelegable(HohenheimSources.ADMIN_SYSTEM.value())).as("step 1: and never delegable")
            .isFalse();
        assertThat(HohenheimSettings.AuthProteus.ENDPOINT.isHostOnly()).as("step 1: the endpoint is host-only")
            .isTrue();
        assertThat(HohenheimSettings.AuthProteus.REALM_CLIENT.getWritePermission())
            .as("step 1: the rest of the login asks the system tier").isEqualTo(HohenheimSources.ADMIN_SYSTEM);

        // 2. The delegated admin opens the settings page: the endpoint row is host-only and the realm client row
        //    read-only, neither with an input, while an ordinary proxy setting keeps its input.
        HttpResponse<String> read = httpGet(PROXY_AUTH_SECTION, delegatedAdmin.token());
        assertThat(read.statusCode()).as("step 2: the ordinary admin tier opens the settings (HTTP %s)",
            read.statusCode()).isEqualTo(200);
        assertThat(read.body()).as("step 2: the endpoint row says only the host sets it")
            .contains("data-cms-setting-host-only");
        assertThat(read.body()).as("step 2: and the realm client says the viewer may not change it")
            .contains("data-cms-setting-not-permitted");
        assertThat(hasInput(read.body(), "proxy.auth_proteus.endpoint")).as("step 2: no endpoint input").isFalse();
        assertThat(hasInput(read.body(), "proxy.auth_proteus.realm_client")).as("step 2: no realm client input")
            .isFalse();
        assertThat(hasInput(adminGet("/admin/settings?section=setting-proxy-proxy").body(), "proxy.proxy.force_https"))
            .as("step 2: an ordinary setting keeps its input").isTrue();
        assertThat(hasInput(httpGet("/admin/settings?section=setting-proxy-proxy", delegatedAdmin.token()).body(),
            "proxy.proxy.force_https")).as("step 2: for the delegated admin too").isTrue();

        // 3. A crafted write of both keys changes neither.
        String endpointBefore = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ENDPOINT);
        String realmBefore = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.REALM_CLIENT);
        httpPostForm("/admin/settings", "proxy.auth_proteus.endpoint=http%3A%2F%2F127.0.0.1%3A8080%2F"
            + "&proxy.auth_proteus.realm_client=elsewhere", delegatedAdmin.token(), delegatedAdmin.csrf());
        assertThat((Object) Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ENDPOINT))
            .as("step 3: the endpoint is unchanged").isEqualTo(endpointBefore);
        assertThat((Object) Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.REALM_CLIENT))
            .as("step 3: the realm client is unchanged").isEqualTo(realmBefore);

        // 4. The operator, who holds "*", gets the realm client's input, but the endpoint stays host-only for them too.
        HttpResponse<String> operator = adminGet(PROXY_AUTH_SECTION);
        assertThat(operator.statusCode()).as("step 4: the operator opens the settings").isEqualTo(200);
        assertThat(hasInput(operator.body(), "proxy.auth_proteus.realm_client")).as("step 4: the realm client input")
            .isTrue();
        assertThat(hasInput(operator.body(), "proxy.auth_proteus.endpoint")).as("step 4: still no endpoint input")
            .isFalse();
        adminPostForm("/admin/settings", "proxy.auth_proteus.endpoint=http%3A%2F%2F127.0.0.1%3A8080%2F");
        assertThat((Object) Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ENDPOINT))
            .as("step 4: the operator's crafted write changes nothing either").isEqualTo(endpointBefore);

        // 5. The framework's settings stay the system tier's: the delegated admin is shown no security header row at
        //    all, while the operator edits it.
        String headers = "/admin/settings?section=setting-framework-security_headers";
        assertThat(httpGet(headers, delegatedAdmin.token()).body())
            .as("step 5: no framework security header row for the delegated admin")
            .doesNotContain("framework.security_headers.csp");
        assertThat(hasInput(adminGet(headers).body(), "framework.security_headers.csp"))
            .as("step 5: the operator's security header input").isTrue();
    }

    /** Whether the page renders a control named exactly {@code name}, never counting its hidden baseline sibling. */
    private static boolean hasInput(String html, String name) {
        return Pattern.compile("name=[\"']" + Pattern.quote(name) + "[\"']").matcher(html).find();
    }
}
