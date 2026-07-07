package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.PageEndpoint;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Tests that server-side exceptions produce visible, useful errors instead of
 * silent failures. The throwing endpoint is registered by THIS test class --
 * production no longer ships a /_test/error route.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ErrorHandlingTest extends HohenheimTestBase {

    /** Test-owned deliberately-throwing endpoint; self-registers at class load. */
    static final PageEndpoint TEST_ERROR = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheimtest", "test_error"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("_test").addDelimiter().addStatic("error").build())
        .build();

    @BeforeAll
    static void registerErrorEndpoint() {
        TEST_ERROR.setHandler(conduit -> {
            throw new RuntimeException("Deliberate test error");
        });
    }

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
        navigateToApp("/admin");
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
        navigateToApp("/admin");
        waitForHydration();

        softNavToError();

        // Sidebar should still be intact -- the layout wasn't destroyed
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);
        assertThat(page.locator(".cms-brand").textContent()).contains("Hohenheim");

        getCollectedErrors().clear();
    }

    @Test
    @Order(4)
    void canRecoverFromErrorViaFullNavigation() {
        navigateToApp("/admin");
        waitForHydration();

        softNavToError();
        getCollectedErrors().clear();

        // After an error, a full page navigation should still work
        navigateToApp("/admin/sites");
        waitForHydration();

        assertThat(page.locator("h1").first().textContent()).contains("Sites");
    }
}
