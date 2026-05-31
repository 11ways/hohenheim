package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.identity.IdentityProvider;
import be.elevenways.zenit.auth.identity.IdentityResolution;
import be.elevenways.zenit.auth.identity.LoginInitiation;
import be.elevenways.zenit.auth.identity.RemoteIdentity;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.common.conduit.Conduit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies hohenheim's SSO-provider integration end to end through the running admin server:
 * a registered identity provider (Proteus uses the same path) appears on the login page and its
 * start route redirects into the provider flow. Uses a fake provider so no external SSO server
 * is needed; the Proteus protocol itself is covered by zenit-auth.
 */
class SsoLoginTest extends HohenheimTestBase {

    @BeforeAll
    static void registerProvider() {
        IdentityProviderRegistry.register(new FakeProvider(), AutoProvisioningSink.builder().build());
    }

    private HttpResponse<String> get(String path, boolean follow) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(follow ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
            .build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void loginPageOffersBothPasswordAndProvider() throws Exception {
        HttpResponse<String> response = get("/login", false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("name=\"password\"");          // password login option
        assertThat(response.body()).contains("/login/sso-test/start");      // provider login option
        assertThat(response.body()).contains("SSO Test");
    }

    @Test
    void providerStartRedirectsIntoTheFlow() throws Exception {
        HttpResponse<String> response = get("/login/sso-test/start", false);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/login/sso-test/callback");
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
                new RemoteIdentity("sso-test", "subject-1", "sso@example.com", "SSO User", Map.of()));
        }
    }
}
