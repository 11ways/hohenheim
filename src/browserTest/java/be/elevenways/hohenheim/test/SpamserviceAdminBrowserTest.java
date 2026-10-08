package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionToken;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.server.flash.Flash;
import be.elevenways.zenit.test.support.EndpointConduit;
import be.elevenways.zenit.test.support.FlashHandoff;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Browser coverage for disconnected Spamservice administration and canonical /admin routes. */
class SpamserviceAdminBrowserTest extends HohenheimTestBase {

    /** Overview, installation, settings and reputation all live inside the hohenheim admin. */
    @Test
    void disconnectedSpamserviceAdminJourney() {
        navigateToApp("/admin/spamservice");
        waitForHydration();

        // The front door is named for what it DOES, not for the daemon behind it: the
        // product-neutral label is what a newcomer scanning the sidebar reads.
        assertThat(page.locator("h1").innerText()).contains("Abuse protection");
        assertThat(page.locator("pl-alert").innerText()).contains("not connected");
        assertThat(page.locator("pl-card").count()).isGreaterThanOrEqualTo(2);
        // It is also the ONLY nav entry for this subsystem now, so it must link the five
        // sub-resources plus the reputation page it swallowed.
        assertThat(page.locator("pl-nav-item[href='/admin/spamservice-installation']").count())
            .isEqualTo(1);
        assertThat(page.locator(".hh-spamservice-sections pl-nav-item").count()).isEqualTo(6);

        // The installation form never renders the controller key.
        navigateToApp("/admin/spamservice-installation");
        waitForHydration();

        assertThat(page.locator("pl-switch[name='enabled']").count()).isEqualTo(1);
        assertThat(page.locator("input[type='hidden'][name='enabled']").count()).isEqualTo(1);
        assertThat(page.locator("[name='working_directory']").count()).isZero();
        assertThat(page.locator("pl-number-input[name='port']").count()).isEqualTo(1);
        assertThat(page.locator("zf-relation-field").count()).isEqualTo(1);
        assertThat(page.locator("pl-number-input[name='max_heap_mb']").count()).isEqualTo(1);
        assertThat(page.locator("[name='controller_key']").count()).isZero();
        assertThat(page.content()).doesNotContain("controller_key");

        navigateToApp("/admin/settings");
        waitForHydration();
        assertThat(page.locator(".cms-settings-nav a[data-cms-settings-nav='setting-spamservice']").innerText())
            .as("the settings mount uses the same product-neutral label").isEqualTo("Abuse protection");

        navigateToApp("/admin/spamservice-reputation");
        waitForHydration();
        assertThat(page.locator("pl-input[name='ip']").count()).isEqualTo(1);
        assertThat(page.locator("form[action='/admin/spamservice-reputation']").count()).isEqualTo(1);
    }

    /** App-owned Spamservice pages leave flash extraction to the CMS dispatch. */
    @Test
    void appOwnedSpamservicePageRendersThePendingFlash() throws Exception {
        Session session = storedSession();
        // The admin session is shared by the whole JVM, but the base drains its pending flash before each test, so
        // nothing another test left untaken leaks in and the one notice stashed here is all that waits.
        assertThat(FlashHandoff.pending(storedSession()))
            .as("the base drained the shared session before this test").isZero();
        // Stashed the way an untabbed full-page request of this session does, never by writing its layout.
        Flash.stash(EndpointConduit.fullPageRequest().withSession(session),
            Microcopy.of("saved").withFilter("scope", "settings"), FlashLevel.ERROR);
        Zenit.getSessionStore().save(session);

        var response = adminGet("/admin/spamservice");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
            .as("the app-owned page must render the centrally injected flash")
            .contains("data-flash-toast");
        assertThat(FlashHandoff.pending(storedSession())).as("rendering consumed the one-shot flash").isZero();
    }

    /** The admin session as the store holds it now, looked up afresh so a render's writes are seen. */
    private static @NonNull Session storedSession() {
        Session session = Zenit.getSessionStore().get(SessionToken.of(sessionToken));
        assertThat(session).as("the session survives the render").isNotNull();
        return session;
    }
}
