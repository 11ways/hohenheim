package be.elevenways.hohenheim.test;

import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

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
}
