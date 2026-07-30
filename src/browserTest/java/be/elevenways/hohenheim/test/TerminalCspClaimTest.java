package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.security.ContentSecurityPolicies;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The terminal's scoped-CSP variant over the REAL app: the processes subpage --
 * the only page that boots ghostty -- carries the wasm concessions, and every
 * other admin page carries the strict policy. Both concessions used to live in
 * the shared STRICT_ADMIN constant and therefore rode every admin surface.
 */
class TerminalCspClaimTest extends HohenheimTestBase {

    @Test
    void terminalConcessionsRideOnlyTheProcessesSubpage() throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

        // 1. An ordinary admin page is served the strict policy, with NEITHER
        //    terminal concession on it.
        String listCsp = csp(client, "/admin/sites");
        assertThat(listCsp)
            .as("step 1: an admin list page must be served the strict admin policy")
            .isEqualTo(ContentSecurityPolicies.STRICT_ADMIN);
        assertThat(listCsp)
            .as("step 1: no ordinary admin page may permit wasm compilation")
            .doesNotContain("wasm-unsafe-eval");
        assertThat(listCsp)
            .as("step 1: no ordinary admin page may permit data: fetches")
            .doesNotContain("connect-src 'self' data:");

        // 2. The processes subpage -- INSIDE that same claimed panel subtree --
        //    is served the widened variant instead. The claim is on the route, so
        //    it holds whatever the page itself answers (this request is anonymous).
        String terminalCsp = csp(client, "/admin/sites/1/page/processes");
        assertThat(terminalCsp)
            .as("step 2: the terminal subpage must be served the terminal variant")
            .isEqualTo(ContentSecurityPolicies.STRICT_ADMIN_TERMINAL);
        assertThat(terminalCsp)
            .as("step 2: the variant must permit compiling ghostty's wasm")
            .contains("script-src 'self' 'wasm-unsafe-eval'");
        assertThat(terminalCsp)
            .as("step 2: the variant must permit FETCHING the data: wasm URI")
            .contains("connect-src 'self' data:");

        // 3. The narrowing is per PAGE, not per subtree: a sibling subpage of the
        //    same record keeps the strict policy.
        assertThat(csp(client, "/admin/sites/1/page/domains"))
            .as("step 3: a sibling subpage must not inherit the terminal concessions")
            .isEqualTo(ContentSecurityPolicies.STRICT_ADMIN);

        // 4. A path outside any registered panel is claimed by neither wiring.
        assertThat(csp(client, "/not-a-panel/sites/1/page/processes"))
            .as("step 4: the variant must not claim a path outside a registered panel")
            .isNotEqualTo(ContentSecurityPolicies.STRICT_ADMIN_TERMINAL);
    }

    private String csp(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.headers().firstValue("Content-Security-Policy").orElse("");
    }

    private String baseUrl() {
        return "http://localhost:" + getServerPort();
    }
}
