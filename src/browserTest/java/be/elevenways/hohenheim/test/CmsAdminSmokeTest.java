package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the zenit-cms admin panel: the shell renders, the sidebar
 * carries the resources, and the dashboard is the landing page.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CmsAdminSmokeTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void panelLandingRedirectsToDashboard() {
        navigateToApp("/admin");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("Hohenheim");
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void sidebarListsTheResources() {
        navigateToApp("/admin");
        waitForHydration();

        String sidebar = page.locator("pl-app-sidebar").textContent();
        assertThat(sidebar).contains("Sites");
        assertThat(sidebar).contains("Certificates");
        assertThat(sidebar).contains("Access Lists");
        assertThat(sidebar).contains("Auth Providers");
        assertThat(sidebar).contains("Databases");
        assertThat(sidebar).contains("Servers");
        assertThat(sidebar).contains("Notification Channels");
        assertThat(sidebar).contains("Activity");
        assertThat(sidebar).contains("Settings");
    }

    @Test
    @Order(3)
    void sitesListRenders() {
        navigateToApp("/admin/sites");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("Sites");
    }

    @Test
    @Order(4)
    void settingsPageRenders() {
        navigateToApp("/admin/settings");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("Proxy");
        assertThat(content).contains("Let's Encrypt");
    }
}
