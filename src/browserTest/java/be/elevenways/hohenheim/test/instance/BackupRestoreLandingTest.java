package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * "Restore to new instance" on a backup's page ends where the restore did: on the new instance, with a toast naming it.
 *
 * AIDEV-NOTE: walked on daystrom 2026-10-13 (round r2y), the restore finished while the operator saw no feedback; the
 * result step answered a Refresh, which returns to the backup page and offers no way to the new instance.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class BackupRestoreLandingTest extends HohenheimTestBase {

    private static final String DIALOG = ".pl-alertdialog-modal[data-open]";
    private static final String HOST_PICK = DIALOG + " pl-field[data-path='serverId'] .pl-select-field";

    private static int hostId;
    private static int backupId;

    @BeforeAll
    static void seed() throws Exception {
        FakeNativeDaemons.register();
        Path staging = Files.createTempDirectory("hohenheim-restore-landing-staging");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.STAGING_PATH, staging.toAbsolutePath().toString());
        Path targetRoot = Files.createTempDirectory("hohenheim-restore-landing-target");

        hostId = HostFixtures.admittedIncusHost("restore-landing-host");
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "restore-landing-target");
        target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        target.set(BackupTargetModel.SETTINGS, Map.of("path", targetRoot.toAbsolutePath().toString()));
        Models.get(BackupTargetModel.class).save(target);

        int instanceId = BackupLaneFixture.instanceRecord("restore-landing-source", hostId);
        new InstanceService().deploy(instanceId);
        backupId = new InstanceBackups().backupNow(instanceId, target.get(BackupTargetModel.ID),
            new FilesystemBackupTarget(targetRoot));
    }

    @Test
    void aRestoreToANewInstanceLandsOnTheNewInstanceWithItsToast() {
        navigateToApp("/admin/instance-backups/" + backupId);
        waitForHydration();
        int newestBefore = newestInstanceId();

        // 1. The page's restore opens its input dialog, and the operator picks the host.
        page.locator("pl-button[data-action-id='hohenheim:restore_backup']").first().click();
        awaitStep("step 1: the restore opens its dialog", () -> page.locator(DIALOG).count() > 0);
        awaitStep("step 1: the dialog asks for the host", () -> page.locator(HOST_PICK).count() > 0);
        page.click(HOST_PICK);
        String option = "he-bottom .pl-select-popup[data-open] div[role='option'][data-value='" + hostId + "']";
        page.waitForSelector(option);
        page.click(option);

        // 2. Confirmed, the restore creates the new instance.
        page.click(DIALOG + " pl-button[data-cms-confirm-ok]");
        awaitStep("step 2: the restore created a new instance", () -> newestInstanceId() > newestBefore);
        int newId = newestInstanceId();

        // 3. The operator is told, by a toast naming the new instance.
        awaitStep("step 3: a toast tells the restore finished", () -> page.locator("pl-toast").count() > 0);
        assertThat(page.locator("pl-toast").first().textContent())
            .as("step 3: the toast names the new instance").contains("#" + newId);

        // 4. And lands on the new instance, the record the restore made.
        awaitStep("step 4: the page is the new instance's", () -> page.url().contains("/admin/instances/" + newId));
    }

    private static int newestInstanceId() {
        Row newest = Models.get(InstanceModel.class).find().orderBy(InstanceModel.ID, SortOrder.DESC).first();
        return newest == null ? 0 : newest.get(InstanceModel.ID);
    }

    private void awaitStep(String step, BooleanSupplier condition) {
        try {
            page.waitForCondition(condition, new Page.WaitForConditionOptions().setTimeout(20_000));
        } catch (PlaywrightException timedOut) {
            String dialog = page.locator(DIALOG).count() > 0 ? page.locator(DIALOG).first().innerHTML() : "no dialog";
            fail(step + " (still on " + page.url() + "; dialog: "
                + dialog.substring(0, Math.min(dialog.length(), 4000)) + ")");
        }
    }
}
