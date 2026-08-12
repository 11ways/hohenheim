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
    void panelShellSidebarListsAndSettingsRender() {
        navigateToApp("/admin");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("Hohenheim");
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);

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

        // The zenit-auth resources are wired into THIS panel's security group. Their own
        // behaviour (create/edit/toggle/grants/roles journeys) is zenit-auth's to prove --
        // AuthCmsResourcesIntegrationTest:194/292/382 -- and hohenheim used to re-prove all
        // of it through the browser for 31.8s. What is hohenheim's is only the wiring.
        assertThat(page.locator("pl-app-sidebar a[href='/admin/users']").count())
            .as("zenit-auth's users resource is mounted in the hohenheim panel")
            .isEqualTo(1);
        assertThat(page.locator("pl-app-sidebar a[href='/admin/roles']").count())
            .as("zenit-auth's roles resource is mounted in the hohenheim panel")
            .isEqualTo(1);

        // Soft nav to the sites list keeps the page cost down.
        page.locator("pl-app-sidebar a[href='/admin/sites']").click();
        page.waitForCondition(() -> {
            var el = page.querySelector("h1");
            return el != null && el.textContent().contains("Sites");
        });
        assertThat(page.content()).contains("Sites");

        navigateToApp("/admin/settings");
        waitForHydration();

        content = page.content();
        assertThat(content).contains("Proxy");
        assertThat(content).contains("Let's Encrypt");
    }
}
