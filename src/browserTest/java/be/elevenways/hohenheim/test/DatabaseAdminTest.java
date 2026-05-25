package be.elevenways.hohenheim.test;

import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-level test for the database admin UI: list page, create form fields, and the sidebar
 * link. Provisioning itself is covered by DatabaseServiceTest; this stays fast and Docker-free
 * (the list is empty on a fresh test DB, so no container inspection happens).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseAdminTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void databasesListRendersEmptyState() {
        navigateToApp("/databases");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Databases");
        assertThat(body).contains("Create Database");
        assertThat(body).contains("No databases yet");
    }

    @Test
    @Order(2)
    void createFormShowsEngineAndStorageFields() {
        navigateToApp("/databases/create");
        waitForHydration();

        String form = page.locator("form[action='/databases/create']").textContent();
        assertThat(form).contains("Engine");
        assertThat(form).contains("Database Name");
        assertThat(form).contains("Ephemeral Storage");
    }

    @Test
    @Order(3)
    void sidebarLinksToDatabases() {
        navigateToApp("/");
        waitForHydration();

        PlaywrightAssertions.assertThat(
            page.locator("a.hh-sidebar__link[href='/databases']")).hasCount(1);
    }
}
