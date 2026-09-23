package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.http.HttpMethod;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthRegistry;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.common.routing.Endpoint;
import be.elevenways.zenit.common.routing.EndpointRoute;
import be.elevenways.zenit.common.routing.PageEndpoint;
import be.elevenways.zenit.server.setting.ServerSettings;
import com.microsoft.playwright.Locator;
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

    /** The path prefix of the test-owned route. */
    private static final String TEST_ERROR_PREFIX = "/_test";

    /** Test-owned deliberately-throwing endpoint; self-registers at class load. */
    static final PageEndpoint TEST_ERROR = Endpoint.pageBuilder()
        .identifier(Identifier.of("hohenheimtest", "test_error"))
        .addRoute(EndpointRoute.builder().setMethod(HttpMethod.GET)
            .addStatic("_test").addDelimiter().addStatic("error").build())
        .build();

    /**
     * Where the admin shell states a failed soft navigation: the flash toast its
     * pl-flash-region draws.
     *
     * AIDEV-NOTE: zenit-cms's cms-feedback CLAIMS navigation refusals (Navigation.claimRefusals),
     * so hawkeye's own banner stays away and the refusal is worded in the CMS's voice as a
     * one-shot flash. Hawkeye's "Server error: ..." is the CONSOLE's diagnostic only, so the old
     * wait for that phrase in the page text never ended.
     */
    private static final String FAILURE_SURFACE = "[data-flash-toast]";

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

        // Settles on ANY outcome the assertions judge, the wrong ones included, so a wrong
        // outcome fails on its assertion message instead of on a 45 s timeout.
        page.waitForCondition(() -> {
            Locator surface = page.locator(FAILURE_SURFACE);
            String body = page.locator("body").textContent();
            return (surface.count() > 0 && !surface.first().textContent().isBlank())
                || body.contains("Deliberate test error") || body.contains("terminal content");
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
        HttpResponse<String> withheld = errorResponse(true);

        assertThat(withheld.statusCode()).isEqualTo(500);
        assertThat(withheld.body()).isNotEmpty();
        assertThat(withheld.body())
            .as("the exception's own words stay on the server")
            .doesNotContain("Deliberate test error");
        assertThat(withheld.body())
            .as("but the failure is stated, and stays machine-readable")
            .contains("INTERNAL_ERROR");

        // 2. Client-facing detail deliberately switched on: a SIGNED-IN request still reads
        //    none of it. zenit's ErrorDetails never exposes internals to an identified
        //    principal, whatever the deployment says -- a product's users never receive them.
        ServerSettings.VALUES.setValue(ServerSettings.Debugging.EXPOSE_ERROR_DETAILS, true);
        AuthRegistry.Snapshot registry = AuthRegistry.snapshot();
        try {
            assertThat(errorResponse(true).body())
                .as("step 2: an identified request never reads the exception, exposure on or not")
                .doesNotContain("Deliberate test error")
                .contains("INTERNAL_ERROR");

            // 3. The same failure answering an ANONYMOUS caller carries the real message, so
            //    step 1 is a WITHHOLDING and not a lost error. The test-owned route is opened
            //    to anonymous callers for this step only (hohenheim gates "/" behind login).
            AuthRegistry.registerPublicPrefix(TEST_ERROR_PREFIX);
            assertThat(errorResponse(false).body())
                .as("step 3: expose_error_details brings the exception's words back")
                .contains("Deliberate test error");
        } finally {
            AuthRegistry.restore(registry);
            ServerSettings.VALUES.setValue(ServerSettings.Debugging.EXPOSE_ERROR_DETAILS, null);
        }
    }

    private HttpResponse<String> errorResponse(boolean signedIn) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + TEST_ERROR_PREFIX + "/error"))
            .GET();
        if (signedIn) {
            request.header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
        Locator surface = page.locator(FAILURE_SURFACE);
        assertThat(surface.count())
            .as("the error surfaces instead of the navigation silently failing").isEqualTo(1);
        assertThat(surface.getAttribute("variant"))
            .as("as a failure, not a notice").isEqualTo(FlashLevel.ERROR.variant());
        assertThat(surface.textContent())
            .as("stated in words, and without quoting the exception in this posture")
            .isNotBlank()
            .doesNotContain("Deliberate test error");
        String bodyText = page.locator("body").textContent();
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
