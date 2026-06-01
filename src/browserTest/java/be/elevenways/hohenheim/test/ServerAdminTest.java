package be.elevenways.hohenheim.test;

import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-level test for the server inventory admin UI: the list shows the seeded local host, the
 * add form exposes the SSH target field, and the sidebar links to it.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerAdminTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void serversListShowsLocalHost() {
        navigateToApp("/servers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Servers");
        assertThat(body).contains("local");        // ensureLocal() seeded the implicit host
        assertThat(body).contains("Add Server");
    }

    @Test
    @Order(2)
    void addFormShowsSshTargetField() {
        navigateToApp("/servers/create");
        waitForHydration();

        String form = page.locator("form[action='/servers/create']").textContent();
        assertThat(form).contains("SSH Target");
        assertThat(form).contains("Name");
    }

    @Test
    @Order(3)
    void sidebarLinksToServers() {
        navigateToApp("/");
        waitForHydration();

        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/servers']")).hasCount(1);
    }
}
