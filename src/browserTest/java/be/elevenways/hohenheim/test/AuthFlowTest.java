package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests the authentication flow: login, logout, session protection, setup wizard.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void unauthenticatedRequestRedirectsToLogin() {
        // Navigate without the session cookie
        page.context().clearCookies();
        page.navigate("http://localhost:" + getServerPort() + "/");
        page.waitForURL("**/login");
        assertThat(page.url()).contains("/login");
    }

    @Test
    @Order(2)
    void loginPageRendersForm() {
        page.context().clearCookies();
        page.navigate("http://localhost:" + getServerPort() + "/login");
        waitForHydration();
        assertThat(page.locator("form[action='/login']").count()).isEqualTo(1);
        assertThat(page.locator("pl-input").count()).isGreaterThanOrEqualTo(2);
        assertThat(page.content()).contains("Sign In");
    }

    @Test
    @Order(3)
    void loginWithInvalidCredentialsShowsError() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:" + getServerPort() + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("email=wrong@example.com&password=wrong"))
            .build();

        java.net.http.HttpResponse<String> response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Invalid email or password");
    }

    @Test
    @Order(4)
    void loginWithValidCredentialsRedirects() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:" + getServerPort() + "/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                "email=test@hohenheim.local&password=testpassword123"))
            .build();

        java.net.http.HttpResponse<String> response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/");
        assertThat(response.headers().allValues("Set-Cookie").toString()).contains("hh_session");
    }

    @Test
    @Order(5)
    void authenticatedDashboardAccessWorks() {
        navigateToApp("/");
        waitForHydration();
        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Dashboard");
    }

    @Test
    @Order(6)
    void logoutRedirectsToLogin() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:" + getServerPort() + "/logout"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", "hh_session=" + sessionToken)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(""))
            .build();

        java.net.http.HttpResponse<String> response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/login");
    }

    @Test
    @Order(7)
    void staticAssetsAreAccessibleWithoutAuth() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:" + getServerPort() + "/hohenheim.css"))
            .GET()
            .build();

        java.net.http.HttpResponse<String> response = client.send(request,
            java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hh-sidebar");
    }
}
