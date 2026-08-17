package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.flash.CmsFlash;
import be.elevenways.zenit.cms.common.flash.FlashEncoding;
import be.elevenways.zenit.cms.common.flash.FlashToast;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionToken;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Browser coverage for disconnected Spamservice administration and canonical /admin routes. */
class SpamserviceAdminBrowserTest extends HohenheimTestBase {

    /** Overview, installation, settings and reputation all live inside the hohenheim admin. */
    @Test
    void disconnectedSpamserviceAdminJourney() {
        navigateToApp("/admin/spamservice");
        waitForHydration();

        assertThat(page.locator("h1").innerText()).contains("Spamservice");
        assertThat(page.locator("pl-alert").innerText()).contains("not connected");
        assertThat(page.locator("pl-card").count()).isGreaterThanOrEqualTo(2);

        // The installation form never renders the controller key.
        navigateToApp("/admin/spamservice-installation");
        waitForHydration();

        assertThat(page.locator("pl-switch[name='enabled']").count()).isEqualTo(1);
        assertThat(page.locator("input[type='hidden'][name='enabled']").count()).isEqualTo(1);
        assertThat(page.locator("[name='working_directory']").count()).isZero();
        assertThat(page.locator("pl-input[name='port'][type='number']").count()).isEqualTo(1);
        assertThat(page.locator("zf-relation-field").count()).isEqualTo(1);
        assertThat(page.locator("pl-input[name='max_heap_mb'][type='number']").count()).isEqualTo(1);
        assertThat(page.locator("[name='controller_key']").count()).isZero();
        assertThat(page.content()).doesNotContain("controller_key");

        navigateToApp("/admin/settings");
        waitForHydration();
        assertThat(page.locator("body").innerText()).contains("Spamservice");

        navigateToApp("/admin/spamservice-reputation");
        waitForHydration();
        assertThat(page.locator("pl-input[name='ip']").count()).isEqualTo(1);
        assertThat(page.locator("form[action='/admin/spamservice-reputation']").count()).isEqualTo(1);
    }

    /** App-owned Spamservice pages leave flash extraction to the CMS dispatch. */
    @Test
    void appOwnedSpamservicePageRendersThePendingFlash() throws Exception {
        Session session = Zenit.getSessionStore().get(SessionToken.of(sessionToken));
        assertThat(session).isNotNull();
        session.set(CmsFlash.PENDING_BY_TAB, Map.of(CmsFlash.UNTABBED,
            FlashEncoding.encode(new FlashToast(Microcopy.of("saved").withFilter("scope", "settings"),
                CmsActionResult.Toast.Level.ERROR))));
        Zenit.getSessionStore().save(session);

        var response = adminGet("/admin/spamservice");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
            .as("the app-owned page must render the centrally injected flash")
            .contains("data-cms-toast");
        Session consumed = Zenit.getSessionStore().get(SessionToken.of(sessionToken));
        assertThat(consumed).isNotNull();
        assertThat(consumed.get(CmsFlash.PENDING_BY_TAB))
            .as("rendering consumes the one-shot flash")
            .isNull();
    }
}
