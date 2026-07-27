package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Render-level test for the database admin resource: list page, create form,
 * sidebar link, and the restore tab. Provisioning itself is covered by
 * DatabaseServiceTest; this stays fast and Docker-free.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseAdminTest extends HohenheimTestBase {

    /** The list, the sidebar entry and the create form's engine/storage fields. */
    @Test
    @Order(1)
    void databaseListAndCreateFormRender() {
        navigateToApp("/admin/databases");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Databases");

        // The shell sidebar carries the databases entry.
        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/admin/databases']")).hasCount(1);

        navigateToApp("/admin/databases/new");
        waitForHydration();

        String content = page.content();
        assertThat(content).contains("engine");
        assertThat(content).contains("db_name");
        assertThat(content).contains("ephemeral");
    }

    /** An existing database is a read-only detail with a delete form and a restore tab. */
    @Test
    @Order(2)
    void existingDatabaseDetailAndRestoreTab() {
        // Insert a record directly (no real container) so the render test stays fast.
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
        Integer id = row.get(DatabaseModel.ID);
        try {
            navigateToApp("/admin/databases/" + id + "/page/restore");
            waitForHydration();

            String body = page.locator("body").textContent();
            assertThat(body).contains("Connection");
            assertThat(body).contains("detailuser");
            assertThat(body).contains("detaildb");
            PlaywrightAssertions.assertThat(page.locator(
                "form[action='/databases/" + name + "/restore'] input[type='file']")).hasCount(1);
        } finally {
            model.find().where(DatabaseModel.NAME.eq(name)).delete();
        }

        Row readonly = model.createEmptyRow();
        readonly.set(DatabaseModel.NAME, "readonlydb");
        readonly.set(DatabaseModel.ENGINE, "postgres");
        readonly.set(DatabaseModel.DB_USER, "readonlyuser");
        readonly.set(DatabaseModel.DB_PASSWORD, "readonlypass");
        readonly.set(DatabaseModel.DB_NAME, "readonlydb");
        model.save(readonly);
        try {
            navigateToApp("/admin/databases/" + readonly.get(DatabaseModel.ID));
            waitForHydration();

            assertThat(page.locator(".cms-form-actions pl-button[type='submit']").count()).isZero();
            assertThat(page.locator("form.cms-delete-form pl-button").count()).isEqualTo(1);
            assertThat(page.locator("input[name='name']").count()).isZero();
        } finally {
            model.delete(readonly);
        }
    }
}
