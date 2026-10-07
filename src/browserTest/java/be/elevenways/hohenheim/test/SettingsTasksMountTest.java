package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelCluster;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.cms.server.task.TaskAdmin;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.orm.SystemTaskModel;
import be.elevenways.zenit.server.task.TaskService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduler is an operator surface of Settings: every scheduled task with Run now, and the runs it started, both
 * tabs of the Settings cluster.
 *
 * Blind spot: this lane runs no task service, so Run now is offered UNAVAILABLE here (the scheduler is down); that a
 * click starts a run is shown on a booted server, where the service runs.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SettingsTasksMountTest extends HohenheimTestBase {

    /** The foreign-key check, a declared Hohenheim task whose schedule every boot reconciles. */
    private static final String TYPE = "hohenheim:check_foreign_keys";

    @Test
    void theSchedulerIsATabOfSettingsAndEachScheduleOffersRunNow() throws Exception {
        // 1. Schedules and runs are members of the Settings cluster, after the settings page.
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        assertThat(admin).as("step 1: the admin panel is registered").isNotNull();
        PanelCluster settings = (PanelCluster) admin.entryBySlug(HohenheimPanel.SETTINGS_CLUSTER);
        assertThat(settings).as("step 1: the Settings cluster is registered").isNotNull();
        assertThat(settings.members()).as("step 1: the scheduler's two lists are tabs of Settings")
            .startsWith(SettingsPage.DEFAULT_SLUG)
            .contains(TaskAdmin.SCHEDULES_SLUG, TaskAdmin.RUNS_SLUG);

        // 2. The schedule the real task reconcile stores for a declared task is listed, with Run now in its row. A
        //    class running after another swapped the database finds the task service stopped (TestDatabases), so the
        //    test starts one over its own database for its duration: initialize() is the boot's own reconcile.
        TaskService service = new TaskService(HohenheimDatabase.datasource());
        try {
            service.initialize();
            assertThat(Models.get(SystemTaskModel.class).find().where(SystemTaskModel.TYPE.eq(TYPE)).count())
                .as("step 2: the reconcile stored the declared schedule").isEqualTo(1);
            navigateToApp("/admin/" + TaskAdmin.SCHEDULES_SLUG);
            waitForHydration();
            Locator row = page.locator("pl-table-body pl-table-row", new Page.LocatorOptions().setHasText(TYPE));
            assertThat(row.count()).as("step 2: the schedule is listed").isEqualTo(1);
            assertThat(row.locator("pl-button, button").filter(new Locator.FilterOptions().setHasText("Run now"))
                .count()).as("step 2: its row offers Run now").isPositive();

            // 3. The runs tab opens beside it.
            navigateToApp("/admin/" + TaskAdmin.RUNS_SLUG);
            waitForHydration();
            assertThat(page.locator("h1").first().innerText().trim()).as("step 3: the runs list renders")
                .isNotEmpty();
        } finally {
            service.shutdown();
        }
    }
}
