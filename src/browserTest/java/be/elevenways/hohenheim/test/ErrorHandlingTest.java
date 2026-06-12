package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.server.AuthCookieSupport;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Tests that server-side exceptions produce visible, useful errors
 * instead of silent failures or vague messages.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ErrorHandlingTest extends HohenheimTestBase {

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }

    /**
     * Trigger a soft navigation to the error endpoint via a synthetic link click.
     * Waits until the browser shows an error or the expected text.
     */
    private void softNavToError() {
        page.evaluate("() => { const a = document.createElement('a'); a.href = '/_test/error'; "
                     + "a.textContent = 'error'; document.body.appendChild(a); a.click(); }");

        page.waitForCondition(() -> {
            String body = page.locator("body").textContent();
            return body.contains("Deliberate test error") || body.contains("Server error")
                || body.contains("navigation error") || body.contains("terminal content");
        });
    }

    @Test
    @Order(1)
    void plainHttpErrorReturns500WithBody() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/_test/error"))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).isNotEmpty();
        assertThat(response.body()).contains("Deliberate test error");
    }

    @Test
    @Order(2)
    void softNavErrorShowsActualErrorMessage() {
        navigateToApp("/");
        waitForHydration();

        softNavToError();

        // The browser should show the actual error message, not a vague one
        String bodyText = page.locator("body").textContent();
        assertThat(bodyText).contains("Deliberate test error");
        assertThat(bodyText).doesNotContain("terminal content");

        // Clear the expected browser error so the test framework doesn't fail on it
        getCollectedErrors().clear();
    }

    @Test
    @Order(3)
    void sidebarSurvivesErrorDuringSoftNav() {
        navigateToApp("/");
        waitForHydration();

        softNavToError();

        // Sidebar should still be intact -- the layout wasn't destroyed
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);
        assertThat(page.locator(".hh-brand").textContent()).isEqualTo("Hohenheim");

        getCollectedErrors().clear();
    }

    @Test
    @Order(4)
    void canRecoverFromErrorViaFullNavigation() {
        navigateToApp("/");
        waitForHydration();

        softNavToError();
        getCollectedErrors().clear();

        // After an error, a full page navigation should still work
        navigateToApp("/sites");
        waitForHydration();

        assertThat(page.locator(".hh-header__title").textContent()).isEqualTo("Sites");
    }
}
