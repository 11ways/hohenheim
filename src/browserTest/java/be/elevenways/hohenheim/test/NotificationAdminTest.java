package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.server.AuthCookieSupport;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Render-level test for the notifications admin UI. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationAdminTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void notificationsListRendersEmptyState() {
        navigateToApp("/notifications");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Notifications");
        assertThat(body).contains("Add Channel");
        assertThat(body).contains("No channels yet");
    }

    @Test
    @Order(2)
    void addFormShowsFormatAndUrlFields() {
        navigateToApp("/notifications/create");
        waitForHydration();

        String form = page.locator("form[action='/notifications/create']").textContent();
        assertThat(form).contains("Format");
        assertThat(form).contains("URL");
    }

    @Test
    @Order(3)
    void sidebarLinksToNotifications() {
        navigateToApp("/");
        waitForHydration();

        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/notifications']")).hasCount(1);
    }

    @Test
    @Order(4)
    void channelEditRoundTrips() throws Exception {
        var create = postForm("/notifications/create",
            "name=ops-room&format=slack&url=https%3A%2F%2Fhooks.example%2Fold");
        assertThat(create.statusCode()).isEqualTo(302);

        navigateToApp("/notifications/ops-room/edit");
        waitForHydration();
        assertThat(page.locator("pl-input[name='url'] input").inputValue())
            .isEqualTo("https://hooks.example/old");

        var update = postForm("/notifications/ops-room",
            "format=discord&url=https%3A%2F%2Fhooks.example%2Fnew");
        assertThat(update.statusCode()).isEqualTo(302);

        navigateToApp("/notifications");
        waitForHydration();
        String body = page.locator("body").textContent();
        assertThat(body).contains("https://hooks.example/new");
        assertThat(body).contains("discord");
    }

    @Test
    @Order(5)
    void testSendReportsDeliveryFailure() throws Exception {
        // A channel pointing at a port nothing listens on -> delivery must report failure.
        var create = postForm("/notifications/create",
            "name=dead-hook&format=generic&url=http%3A%2F%2F127.0.0.1%3A1%2Fhook");
        assertThat(create.statusCode()).isEqualTo(302);

        var test = postForm("/notifications/dead-hook/test", "");
        assertThat(test.statusCode()).isEqualTo(302);
        assertThat(test.headers().firstValue("Location").orElse(""))
            .isEqualTo("/notifications?test=failed&channel=dead-hook");

        navigateToApp("/notifications?test=failed&channel=dead-hook");
        waitForHydration();
        String body = page.locator("body").textContent();
        assertThat(body).contains("Test failed");
        assertThat(body).contains("dead-hook");
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
