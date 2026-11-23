package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.auth.ProteusSignIn;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.zenit.auth.identity.IdentityProvider;
import be.elevenways.zenit.auth.identity.IdentityResolution;
import be.elevenways.zenit.auth.identity.LoginInitiation;
import be.elevenways.zenit.auth.identity.RemoteIdentity;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.setting.SettingDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies hohenheim's SSO-provider integration end to end through the running admin server:
 * a registered identity provider (Proteus uses the same path) appears on the login page and its
 * start route redirects into the provider flow. Uses a fake provider so no external SSO server
 * is needed; the Proteus protocol itself is covered by zenit-auth. Hohenheim's own Proteus wiring
 * is proven here too: it is offered only while it names a required permission.
 */
class SsoLoginTest extends HohenheimHttpTestBase {

    @BeforeAll
    static void registerProvider() {
        IdentityProviderRegistry.register(new FakeProvider(), AutoProvisioningSink.builder().build());
    }

    /** The login page offers the provider next to password login, and its start route enters the flow. */
    @Test
    void registeredProviderIsOfferedAndStartsItsFlow() throws Exception {
        HttpResponse<String> loginPage = httpGet("/login", null);
        assertThat(loginPage.statusCode()).isEqualTo(200);
        assertThat(loginPage.body()).contains("name=\"password\"");          // password login option
        assertThat(loginPage.body()).contains("/login/sso-test/start");      // provider login option
        assertThat(loginPage.body()).contains("SSO Test");

        HttpResponse<String> start = httpGet("/login/sso-test/start", null);
        assertThat(start.statusCode()).isEqualTo(302);
        assertThat(start.headers().firstValue("Location")).hasValue("/login/sso-test/callback");
    }

    /**
     * Proteus sign-in switched on without a required permission is not offered and the dashboard says why; naming the
     * permission offers it next to password login.
     */
    @Test
    void proteusSignInIsOfferedOnlyWithARequiredPermission() throws Exception {
        Map<SettingDefinition<String>, String> texts = new LinkedHashMap<>();
        texts.put(HohenheimSettings.AuthProteus.ENDPOINT, "http://127.0.0.1:9/");
        texts.put(HohenheimSettings.AuthProteus.REALM_CLIENT, "hohenheim");
        texts.put(HohenheimSettings.AuthProteus.ACCESS_KEY, "realm-key");
        texts.put(HohenheimSettings.AuthProteus.REQUIRED_PERMISSION, null);
        Map<SettingDefinition<String>, String> before = new LinkedHashMap<>();
        texts.keySet().forEach(setting -> before.put(setting, Zenit.SETTINGS_VALUES.getValue(setting)));
        Boolean enabledBefore = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ENABLED);

        try {
            // 1. Switched on and set up, naming no required permission: not offered, and the dashboard says so.
            texts.forEach(Zenit.SETTINGS_VALUES::setValue);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.ENABLED, true);
            ProteusSignIn.registerIfConfigured();
            assertThat(IdentityProviderRegistry.get(ProteusSignIn.SLUG)).as("step 1: no provider").isNull();
            assertThat(httpGet("/login", null).body()).as("step 1: the sign-in page offers no Proteus")
                .contains("name=\"password\"")
                .doesNotContain("/login/proteus/start");
            assertThat(heldBackItems()).as("step 1: one attention item says why").hasSize(1);

            // 2. Naming the permission offers Proteus, and the item is gone.
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.REQUIRED_PERMISSION, "hohenheim.robbedoes");
            ProteusSignIn.registerIfConfigured();
            assertThat(IdentityProviderRegistry.get(ProteusSignIn.SLUG)).as("step 2: the provider").isNotNull();
            assertThat(httpGet("/login", null).body()).as("step 2: the sign-in page offers Proteus")
                .contains("/login/proteus/start");
            assertThat(heldBackItems()).as("step 2: no attention item").isEmpty();
        } finally {
            before.forEach(Zenit.SETTINGS_VALUES::setValue);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.ENABLED, enabledBefore);
        }
    }

    /** @return the dashboard's attention items about a held-back Proteus sign-in */
    private static List<AttentionItem> heldBackItems() {
        List<AttentionItem> items = new ArrayList<>();
        AttentionCollector.proteusSignIn(items);
        return items.stream().filter(item -> "proteus_sign_in_held_back".equals(item.title().key())).toList();
    }

    /** Minimal stand-in for a real provider (Proteus/OIDC); no external server required. */
    private static final class FakeProvider implements IdentityProvider {
        @Override public String slug() { return "sso-test"; }
        @Override public String displayName() { return "SSO Test"; }

        @Override public LoginInitiation initiate(Conduit conduit) {
            return LoginInitiation.redirect("/login/sso-test/callback");
        }

        @Override public IdentityResolution complete(Conduit conduit) {
            return IdentityResolution.success(
                // emailVerified TRUE: this fake IS the provider, vouching for its own
                // fixture subject (zenit-auth 4a33483 made the verified signal explicit).
                new RemoteIdentity("sso-test", "subject-1", "sso@example.com", true,
                    "SSO User", Map.of()));
        }
    }
}
