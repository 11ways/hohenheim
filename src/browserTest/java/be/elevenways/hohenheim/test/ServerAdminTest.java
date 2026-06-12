package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.server.AuthCookieSupport;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-level test for the server inventory admin UI: the list shows the seeded local host, the
 * add form exposes the SSH target field, and the sidebar links to it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerAdminTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void serversListShowsLocalHost() {
        navigateToApp("/servers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Servers");
        assertThat(body).contains("local");        // ensureLocal() seeded the implicit host
        assertThat(body).contains("Add Server");
    }

    @Test
    @Order(2)
    void addFormShowsSshTargetField() {
        navigateToApp("/servers/create");
        waitForHydration();

        String form = page.locator("form[action='/servers/create']").textContent();
        assertThat(form).contains("SSH Target");
        assertThat(form).contains("Name");
    }

    @Test
    @Order(3)
    void sidebarLinksToServers() {
        navigateToApp("/");
        waitForHydration();

        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/servers']")).hasCount(1);
    }

    @Test
    @Order(4)
    void serverEditRoundTrips() throws Exception {
        var create = postForm("/servers/create", "name=edge-9&ssh_target=deploy%40edge9.example");
        assertThat(create.statusCode()).isEqualTo(302);

        navigateToApp("/servers/edge-9/edit");
        waitForHydration();
        assertThat(page.locator("pl-input[name='ssh_target'] input").inputValue())
            .isEqualTo("deploy@edge9.example");

        var update = postForm("/servers/edge-9", "ssh_target=ops%40edge9.example");
        assertThat(update.statusCode()).isEqualTo(302);

        navigateToApp("/servers/edge-9/edit");
        waitForHydration();
        assertThat(page.locator("pl-input[name='ssh_target'] input").inputValue())
            .isEqualTo("ops@edge9.example");

        // The implicit local host has no edit page.
        var local = postForm("/servers/local", "ssh_target=evil%40host");
        assertThat(local.statusCode()).isEqualTo(302);
        assertThat(local.headers().firstValue("Location").orElse("")).isEqualTo("/servers");
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
