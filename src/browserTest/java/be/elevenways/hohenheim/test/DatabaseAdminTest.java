package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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

            // The form SAVES, because the resource ceilings are editable after create.
            assertThat(page.locator(".cms-form-actions pl-button[type='submit']").count())
                .as("the detail form offers Save, for the resource ceilings")
                .isEqualTo(1);
            assertThat(page.locator("form.cms-delete-form pl-button").count()).isEqualTo(1);
            // Everything describing the provisioned container stays frozen. A readonly
            // entry renders a static value and NO named control at all (zenit-forms
            // form/plain.hwk), so the absence of the control IS the freeze -- asserted
            // on any element, not on `input`, because a typed entry is a plumage control.
            assertThat(page.locator("[name='name']").count())
                .as("the name describes a provisioned container and stays frozen")
                .isZero();
            assertThat(page.locator("[name='db_user']").count()).isZero();
            assertThat(page.locator("[name='db_password']").count()).isZero();
            // ... while the memory ceiling is writable. Numeric entries are never a
            // native number input (its decimal separator follows the BROWSER locale),
            // so the control to look for is plumage's.
            assertThat(page.locator("pl-number-input[name='memory_limit_mb']").count())
                .as("the memory ceiling is the one thing an operator can correct")
                .isEqualTo(1);
            // The consequence is stated where the fields cannot state it.
            assertThat(page.locator("body").textContent())
                .contains("recreates the engine on the same data volume");
        } finally {
            model.delete(readonly);
        }
    }

    /**
     * COUNTERFACTUAL: creating a database whose name is taken REFUSES, rather than
     * converging the existing record onto the new settings.
     *
     * The create path was a find-by-name-then-overwrite, so a second create for one name
     * rewrote the first record's engine, image, user, password and host in place -- and
     * because DatabaseInstances.dataVolumeOf keys the data volume on the record's NAME,
     * the provision that followed remounted the VICTIM'S DATA under the new credentials
     * while the admin form reported a successful create. The assertions here are on the
     * victim's own columns for exactly that reason: "no second row appeared" would have
     * passed against the broken code.
     */
    @Test
    @Order(3)
    void creatingADatabaseWithATakenNameRefusesInsteadOfSeizingTheExistingOne() {
        DatabaseModel model = Models.get(DatabaseModel.class);
        DatabaseService service = new DatabaseService();
        String victimName = "seizurevictim";
        String freshName = "seizurefresh";

        Row victim = model.createEmptyRow();
        victim.set(DatabaseModel.NAME, victimName);
        victim.set(DatabaseModel.ENGINE, "postgres");
        victim.set(DatabaseModel.IMAGE, "postgres:17-alpine");
        victim.set(DatabaseModel.DB_USER, "victimuser");
        victim.set(DatabaseModel.DB_PASSWORD, "victimpass");
        victim.set(DatabaseModel.DB_NAME, "victimdb");
        victim.set(DatabaseModel.EPHEMERAL, false);
        victim.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        model.save(victim);
        Integer victimId = victim.get(DatabaseModel.ID);

        try {
            // 1. THE ATTACK: the same name, a different engine and different credentials.
            //    An unpullable image keeps any background provision from reaching a daemon.
            Throwable refused = catchThrowable(() -> service.createAsync(victimName,
                ManagedDatabase.Engine.REDIS, "hohenheim-absent-image:notatag",
                "attackeruser", "attackerpass", "attackerdb", false));

            // 2. STATE FIRST, deliberately: the victim is byte-for-byte what it was.
            //    Engine, credentials and the database name are the three that decide which
            //    data volume gets remounted under whose password, and "no second row
            //    appeared" would have passed against the seizing code.
            Row after = model.findById(victimId);
            assertThat((String) after.get(DatabaseModel.ENGINE))
                .as("step 2: the victim's engine was not converged").isEqualTo("postgres");
            assertThat((String) after.get(DatabaseModel.IMAGE))
                .as("step 2: nor its image").isEqualTo("postgres:17-alpine");
            assertThat((String) after.get(DatabaseModel.DB_USER))
                .as("step 2: nor its user").isEqualTo("victimuser");
            assertThat((String) after.get(DatabaseModel.DB_PASSWORD))
                .as("step 2: nor its password").isEqualTo("victimpass");
            assertThat((String) after.get(DatabaseModel.DB_NAME))
                .as("step 2: nor the database inside it").isEqualTo("victimdb");
            assertThat((String) after.get(DatabaseModel.STATUS))
                .as("step 2: and it was not flipped back to provisioning")
                .isEqualTo(DatabaseModel.STATUS_ACTIVE);
            assertThat(model.find().where(DatabaseModel.NAME.eq(victimName)).count())
                .as("step 2: still exactly one record answers to that name").isEqualTo(1);

            // 2b. And the caller was told so, by name, rather than being handed a success.
            assertThat(refused)
                .as("step 2b: a taken name is a typed refusal, not a 500 and not a success")
                .isInstanceOf(Violations.class);
            assertThat(((Violations) refused).all().get(0).message().key())
                .as("step 2b: named, so the form can render it")
                .isEqualTo("database_name_taken");

            // 3. POSITIVE ANCHOR: a free name still creates, and the caller gets back the
            //    row it actually inserted -- not the answer to a re-query by name, which is
            //    how the resource used to report the victim's id as "created".
            Row created = service.createAsync(freshName, ManagedDatabase.Engine.REDIS,
                "hohenheim-absent-image:notatag", "freshuser", "freshpass", "freshdb", false);
            assertThat((String) created.get(DatabaseModel.NAME))
                .as("step 3: the returned row is the one that was created")
                .isEqualTo(freshName);
            assertThat((Integer) created.get(DatabaseModel.ID))
                .as("step 3: and it is a NEW record, never the victim's")
                .isNotNull().isNotEqualTo(victimId);
            assertThat((String) model.findById(created.get(DatabaseModel.ID))
                    .get(DatabaseModel.DB_USER))
                .as("step 3: persisted with its own credentials").isEqualTo("freshuser");
        } finally {
            model.find().where(DatabaseModel.NAME.eq(victimName)).delete();
            model.find().where(DatabaseModel.NAME.eq(freshName)).delete();
        }
    }
}
