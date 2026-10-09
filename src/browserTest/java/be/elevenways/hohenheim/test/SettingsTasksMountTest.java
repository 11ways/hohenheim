package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.cms.HohenheimPanel;
import be.elevenways.hohenheim.server.task.BackupControlPlane;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.server.task.CheckForeignKeys;
import be.elevenways.hohenheim.server.task.CleanOldInstanceLogs;
import be.elevenways.hohenheim.server.task.CleanOrphanCertificates;
import be.elevenways.hohenheim.server.task.ReconcileDockerResources;
import be.elevenways.hohenheim.server.task.SecuritySweep;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelCluster;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.cms.server.task.TaskAdmin;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.orm.SystemTaskModel;
import be.elevenways.zenit.server.task.TaskService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

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
            // The list names a task by its declared label and says its schedule in words (zenit-cms, 2026-10-08).
            String label = new CheckForeignKeys().label().resolve(LocaleChain.ofTags("en"),
                MessageResolvers.getDefault());
            Locator row = page.locator("pl-table-body pl-table-row", new Page.LocatorOptions().setHasText(label));
            assertThat(row.count()).as("step 2: the schedule is listed by its label").isEqualTo(1);
            assertThat(row.innerText()).as("step 2: its cron reads in words, never the raw expression")
                .contains("Every day at 04:41").doesNotContain("41 4 * * *");
            assertThat(row.locator("pl-button, button").filter(new Locator.FilterOptions().setHasText("Run now"))
                .count()).as("step 2: its row offers Run now").isPositive();

            // 3. The runs tab opens beside it, under the cluster's one heading.
            navigateToApp("/admin/" + TaskAdmin.RUNS_SLUG);
            waitForHydration();
            assertThat(page.locator("h1").count()).as("step 3: the runs list has one heading").isEqualTo(1);
            assertThat(page.locator("h1").innerText().trim()).as("step 3: the runs list renders")
                .isNotEmpty();
        } finally {
            service.shutdown();
        }

        // 4. The tasks of board Settings-Tasks are named as the board names them; their descriptions and the board's
        //    "Nightly 03:00" wording are the framework task list's to show (D13e, plan section 39).
        Map<ScheduledTask, String> board = new LinkedHashMap<>();
        board.put(new BackupDatabases(), "Back up databases");
        board.put(new BackupControlPlane(), "Back up the control panel");
        board.put(new SecuritySweep(), "Security sweep");
        board.put(new CleanOldInstanceLogs(), "Clean old console output");
        board.put(new CleanOrphanCertificates(), "Clean unused certificates");
        board.put(new ReconcileDockerResources(), "Tidy Docker");
        for (Map.Entry<ScheduledTask, String> task : board.entrySet()) {
            assertThat(task.getKey().label().resolve(LocaleChain.ofTags("en"), MessageResolvers.getDefault()))
                .as("step 4: " + task.getKey().id() + " reads as the board names it")
                .isEqualTo(task.getValue());
        }
    }
}
