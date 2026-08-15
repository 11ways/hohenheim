package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.PageEndpoint;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Tests that server-side exceptions produce visible errors instead of silent
 * failures, and that "visible" stops at the exception's own words: those are
 * client-facing DETAIL, gated with the stack trace by
 * {@code debugging.expose_error_details}. The throwing endpoint is registered by
 * THIS test class -- production no longer ships a /_test/error route.
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
    void plainHttpErrorReturns500AndSaysNothingAboutTheExceptionUntilAskedTo()
            throws Exception {
        // 1. PRODUCTION posture (what this installation runs): a real 500 with a real
        //    body, and NOT one word of the exception. The message is where the column
        //    name and the absolute path live, so it travels with the trace or not at
        //    all -- an operator reads it in the log.
        HttpResponse<String> withheld = errorResponse();

        assertThat(withheld.statusCode()).isEqualTo(500);
        assertThat(withheld.body()).isNotEmpty();
        assertThat(withheld.body())
            .as("the exception's own words stay on the server")
            .doesNotContain("Deliberate test error");
        assertThat(withheld.body())
            .as("but the failure is stated, and stays machine-readable")
            .contains("INTERNAL_ERROR");

        // 2. And with client-facing detail deliberately switched on, the same request
        //    carries the real message -- so step 1 is a WITHHOLDING and not a lost
        //    error. This is the posture a dev run has with zero configuration.
        ServerSettings.VALUES.setValue(ServerSettings.Debugging.EXPOSE_ERROR_DETAILS, true);
        try {
            assertThat(errorResponse().body())
                .as("expose_error_details brings the exception's words back")
                .contains("Deliberate test error");
        } finally {
            ServerSettings.VALUES.setValue(ServerSettings.Debugging.EXPOSE_ERROR_DETAILS, null);
        }
    }

    private HttpResponse<String> errorResponse() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/_test/error"))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(2)
    void softNavErrorStaysVisibleKeepsTheShellAndRecovers() {
        navigateToApp("/admin");
        waitForHydration();

        softNavToError();

        // The failure REACHES the operator -- a soft nav that silently kept the old
        // page is the bug this test exists for. What it must not do is quote the
        // exception: client-facing detail is withheld in this posture, so the
        // assertion is that an error is stated, not which one.
        String bodyText = page.locator("body").textContent();
        assertThat(bodyText)
            .as("the error surfaces instead of the navigation silently failing")
            .containsAnyOf("Server error", "Something went wrong", "An internal error occurred");
        assertThat(bodyText)
            .as("and it does not quote the exception in this posture")
            .doesNotContain("Deliberate test error");
        assertThat(bodyText).doesNotContain("terminal content");

        // Sidebar should still be intact -- the layout wasn't destroyed
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);
        assertThat(page.locator(".cms-brand").textContent()).contains("Hohenheim");

        // Clear the expected browser error so the test framework doesn't fail on it
        getCollectedErrors().clear();

        // After an error, a full page navigation should still work
        navigateToApp("/admin/sites");
        waitForHydration();

        assertThat(page.locator("h1").first().textContent()).contains("Sites");
    }
}
