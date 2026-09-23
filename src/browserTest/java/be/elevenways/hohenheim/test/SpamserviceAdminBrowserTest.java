package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.session.SessionToken;
import be.elevenways.zenit.common.flash.FlashEncoding;
import be.elevenways.zenit.common.flash.FlashLevel;
import be.elevenways.zenit.server.flash.Flash;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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
        session.set(Flash.PENDING_BY_TAB, Map.of(Flash.UNTABBED,
            FlashEncoding.encode(Microcopy.of("saved").withFilter("scope", "settings"),
                FlashLevel.ERROR, "spamservice-page")));
        Zenit.getSessionStore().save(session);

        var response = adminGet("/admin/spamservice");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
            .as("the app-owned page must render the centrally injected flash")
            .contains("data-flash-toast");
        assertThat(awaitPendingFlash())
            .as("rendering consumes the one-shot flash")
            .isNull();
    }

    /**
     * The session's pending flash once the server had its chance to spend it.
     *
     * AIDEV-NOTE: zenit spends a flash in the render's responseWritten stage, which runs AFTER
     * the body was written and closed -- so the client holds the whole page before the
     * acknowledgement's session write, and reading the store at once races it. Polling with a
     * bound keeps the assertion meaning "spent by that render" without a fixed sleep.
     */
    private static @Nullable Map<String, String> awaitPendingFlash() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Session session = Zenit.getSessionStore().get(SessionToken.of(sessionToken));
            assertThat(session).as("the session survives the render").isNotNull();
            Map<String, String> pending = session.get(Flash.PENDING_BY_TAB);
            if (pending == null || System.nanoTime() >= deadline) {
                return pending;
            }
            Thread.sleep(20);
        }
    }
}
