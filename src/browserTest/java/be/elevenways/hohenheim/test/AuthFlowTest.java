package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth integration: the admin tree is gated by zenit-auth (anonymous -> /login), the login page
 * offers password login, an authenticated session reaches the dashboard, and assets stay public.
 * The login/logout/setup mechanics themselves are owned and tested by zenit-auth.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowTest extends HohenheimTestBase {

    private HttpResponse<String> get(String path, boolean followRedirects) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
            .build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void unauthenticatedRequestRedirectsToLogin() throws Exception {
        HttpResponse<String> response = get("/", false);   // no session cookie
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/login");
    }

    @Test
    @Order(2)
    void loginPageOffersPasswordLogin() throws Exception {
        HttpResponse<String> response = get("/login", false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("action=\"/login\"");
        assertThat(response.body()).contains("name=\"password\"");
    }

    @Test
    @Order(3)
    void authenticatedDashboardAccessWorks() {
        // "/" redirects to the /admin panel, which lands on the dashboard.
        navigateToApp("/admin");
        waitForHydration();
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void staticAssetsAreAccessibleWithoutAuth() throws Exception {
        HttpResponse<String> response = get("/hohenheim.css", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(".hh-deploy-log");
    }
}
