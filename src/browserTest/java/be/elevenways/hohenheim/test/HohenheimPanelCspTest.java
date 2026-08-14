package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.cms.common.CmsSettings;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's own panels carry the strict admin CSP, and the admin shell bootstraps
 * exactly once with no inline handler.
 *
 * The generic mechanism (CSP liveness, injected script/img blocked) is pinned by
 * zenit-cms {@code CspPanelBrowserTest} and the auth families by zenit-auth
 * {@code AuthFlowIntegrationTest}; what is HOHENHEIM's to prove is that its panels claim
 * the policy at all and that its shell does not need an inline bootstrap to start -- and
 * this is the only place in the workspace that counts {@code /_hawkeye/boot.js}
 * occurrences and asserts no {@code onload=} survives.
 *
 * AIDEV-NOTE: this lived inside {@code ProclogProxyIngressTest} until 2026-08-13 and cost
 * the DEFAULT LANE its only copy of that assertion. That class is @Tag("slow") because
 * ONE of its two journeys drives a real node upstream through the tenant proxy listener,
 * and the slow-lane guard tags at CLASS granularity -- so a live sibling evicted a check
 * that needs neither node, nor a proxy, nor a daemon. Keep it that way: nothing here may
 * grow a live dependency, or the guard will (correctly) demand the tag back and the
 * default lane will lose the coverage again.
 */
class HohenheimPanelCspTest extends HohenheimTestBase {

    @Test
    void hohenheimPanelsCarryTheAdminCspAndBootstrapExactlyOnce() throws Exception {
        String expected = CmsSettings.VALUES.getValue(CmsSettings.CSP);
        assertThat(expected).as("the admin CSP default forbids inline script")
            .contains("script-src 'self'");

        // 1. Hohenheim's own panels (/admin, /manage) carry the scoped admin CSP.
        for (String path : List.of("/admin", "/admin/sites", "/manage")) {
            assertThat(adminGet(path).headers().firstValue("Content-Security-Policy").orElse(null))
                .as("panel route " + path + " carries the admin CSP").isEqualTo(expected);
        }

        // 2. An unclaimed public path gets NO CMS CSP (the global default stays opt-in).
        assertThat(adminGet("/definitely-not-a-hohenheim-panel")
                .headers().firstValue("Content-Security-Policy"))
            .as("an unclaimed path carries no admin CSP").isEmpty();

        // 3. The admin shell bootstraps exactly once: one bundle boot script, no inline
        //    onload="main()" (the pre-fix shape that dies silently under the strict CSP).
        String adminHtml = adminGet("/admin/sites").body();
        assertThat(countOccurrences(adminHtml, "/_hawkeye/boot.js"))
            .as("exactly one CSP-safe bootstrap script").isEqualTo(1);
        assertThat(adminHtml).as("no inline onload bootstrap handler survives")
            .doesNotContain("onload=");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
