package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.server.task.CleanOldActivity;
import be.elevenways.hohenheim.server.task.CleanOldProclogs;
import be.elevenways.hohenheim.server.task.CleanOrphanCertificates;
import be.elevenways.hohenheim.server.task.ResignDnssecZones;
import be.elevenways.hohenheim.server.task.SecuritySweep;
import be.elevenways.hohenheim.server.task.UpdateNodeVersions;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.task.UpdateSystemUsers;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.task.orm.SystemTaskModel;
import be.elevenways.zenit.server.task.TaskBootstrap;
import be.elevenways.zenit.server.task.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification of {@code ServerMain}'s {@link TaskBootstrap} wiring: the nine
 * Hohenheim maintenance tasks are discovered and reconciled into {@code system_task}, the four
 * BOOT_AND_CRON tasks fire once at startup, and the five FALLBACK maintenance tasks
 * do not fire at boot (they wait for their daily cron).
 */
class HohenheimTaskBootstrapTest {

    private static TaskService service;

    @BeforeAll
    static void boot() throws Exception {
        File db = File.createTempFile("hohenheim-taskboot", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

        SiteTypes.boot();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();   // auto-discovery creates system_task + the M0xx tables

        service = TaskBootstrap.start(HohenheimDatabase.datasource());
    }

    @AfterAll
    static void shutdown() {
        if (service != null) service.shutdown();
    }

    @Test
    void allNineTasksAreReconciledIntoSystemTask() {
        List<String> types = service.taskModel().findAllSystemRows().stream()
            .map(r -> (String) r.get(SystemTaskModel.TYPE))
            .toList();
        assertThat(types).contains(
            UpdateSystemIpAddresses.class.getName(),
            UpdateSystemUsers.class.getName(),
            UpdateNodeVersions.class.getName(),
            BackupDatabases.class.getName(),
            CleanOldProclogs.class.getName(),
            CleanOldActivity.class.getName(),
            CleanOrphanCertificates.class.getName(),
            ResignDnssecZones.class.getName(),
            SecuritySweep.class.getName());
    }

    @Test
    void bootAndCronDiscoveryTasksFireAtStartup() throws Exception {
        for (String type : List.of(
                UpdateSystemIpAddresses.class.getName(),
                UpdateSystemUsers.class.getName(),
                UpdateNodeVersions.class.getName(),
                SecuritySweep.class.getName())) {
            assertThat(awaitHistory(type))
                .as("BOOT_AND_CRON task should have a history row shortly after boot: " + type)
                .isTrue();
        }
    }

    @Test
    void fallbackTasksDoNotFireAtStartup() {
        for (String type : List.of(
                BackupDatabases.class.getName(),
                CleanOldProclogs.class.getName(),
                CleanOldActivity.class.getName(),
                CleanOrphanCertificates.class.getName(),
                ResignDnssecZones.class.getName())) {
            assertThat(service.historyModel().findRecentForType(type, 5))
                .as("FALLBACK task must NOT fire at boot: " + type)
                .isEmpty();
        }
    }

    /** Poll for up to ~5s because boot fires run async on virtual threads. */
    private static boolean awaitHistory(String type) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (!service.historyModel().findRecentForType(type, 5).isEmpty()) return true;
            Thread.sleep(100);
        }
        return false;
    }
}
