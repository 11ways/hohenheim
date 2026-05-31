package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.orm.datasource.Row;
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

    @Test
    @Order(4)
    void detailPageShowsConnectionInfo() {
        // Insert a record directly (no real container) so the render test stays fast; the detail
        // handler's live-status probe is best-effort and resolves to "stopped" without one.
        DatabaseModel model = Models.get(DatabaseModel.class);
        String name = "detailtest";
        Row row = model.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.IMAGE, "postgres:17-alpine");
        row.set(DatabaseModel.DB_USER, "detailuser");
        row.set(DatabaseModel.DB_PASSWORD, "detailpass");
        row.set(DatabaseModel.DB_NAME, "detaildb");
        row.set(DatabaseModel.EPHEMERAL, false);
        model.save(row);
        try {
            navigateToApp("/databases/" + name);
            waitForHydration();

            String body = page.locator("body").textContent();
            assertThat(body).contains("Connection");
            assertThat(body).contains("detailuser");
            assertThat(body).contains("detaildb");
        } finally {
            model.find().where(DatabaseModel.NAME.eq(name)).delete();
        }
    }
}
