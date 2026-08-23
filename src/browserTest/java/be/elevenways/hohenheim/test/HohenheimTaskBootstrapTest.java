package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.hohenheim.server.task.BackupControlPlane;
import be.elevenways.hohenheim.server.task.BackupDatabases;
import be.elevenways.hohenheim.server.task.CleanOldActivity;
import be.elevenways.hohenheim.server.task.CleanOldInstanceLogs;
import be.elevenways.hohenheim.server.task.CleanOrphanCertificates;
import be.elevenways.hohenheim.server.task.MonitorStacks;
import be.elevenways.hohenheim.server.task.ObserveInstanceDisk;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.hohenheim.server.task.ReclaimDockerImages;
import be.elevenways.hohenheim.server.task.ReconcileDockerResources;
import be.elevenways.hohenheim.server.task.ReconcileInstanceStatus;
import be.elevenways.hohenheim.server.task.ResignDnssecZones;
import be.elevenways.hohenheim.server.task.SecuritySweep;
import be.elevenways.hohenheim.server.task.SuperviseProxyListeners;
import be.elevenways.hohenheim.server.task.UpdateSystemIpAddresses;
import be.elevenways.hohenheim.server.task.UpdateSystemUsers;
import be.elevenways.hohenheim.server.task.VerifyIncusIsolation;
import be.elevenways.hohenheim.server.task.VerifyWorkloadIsolation;
import be.elevenways.zenit.common.task.ScheduleDeclaration;
import be.elevenways.zenit.common.task.ScheduleKind;
import be.elevenways.zenit.common.task.ScheduledTask;
import be.elevenways.zenit.common.task.TaskCatalog;
import be.elevenways.zenit.common.task.TaskDescriptor;
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
 * End-to-end verification of {@code ServerMain}'s {@link TaskBootstrap} wiring, pinned
 * against the DISCOVERED catalog rather than a copied inventory: every Hohenheim
 * {@link ScheduledTask} Protoblast discovered must be named in {@link #PINNED}, every
 * declared schedule must reconcile into {@code system_task}, and which tasks fire at boot
 * is DERIVED from each task's own {@link ScheduleKind} so the declaration and the
 * expectation cannot drift apart.
 *
 * The suite boots with every role enabled (the settings defaults), so no role gate
 * retracts a declaration here.
 */
class HohenheimTaskBootstrapTest {

    /** Only Hohenheim's own tasks are this suite's business; the catalog also holds framework ones. */
    private static final String TASK_PACKAGE = "be.elevenways.hohenheim.";

    /**
     * Every Hohenheim {@link ScheduledTask} that exists.
     *
     * AIDEV-NOTE: this list is the ONLY hand-written thing left here, and it is compared
     * for EXACT equality against the discovered catalog -- adding a task without adding it
     * here fails. That is the point: a new task's schedule kind and role gate get looked at
     * once, deliberately. Everything downstream (which tasks reconcile, which fire at boot)
     * is derived from the declarations, never re-listed.
     */
    private static final List<Class<? extends ScheduledTask>> PINNED = List.of(
        BackupControlPlane.class,
        BackupDatabases.class,
        CleanOldActivity.class,
        CleanOldInstanceLogs.class,
        CleanOrphanCertificates.class,
        MonitorStacks.class,
        ObserveInstanceDisk.class,
        ReapIncusControllers.class,
        ReclaimDockerImages.class,
        ReconcileDockerResources.class,
        ReconcileInstanceStatus.class,
        ResignDnssecZones.class,
        SecuritySweep.class,
        SuperviseProxyListeners.class,
        UpdateSystemIpAddresses.class,
        UpdateSystemUsers.class,
        VerifyIncusIsolation.class,
        VerifyWorkloadIsolation.class);

    private static TaskService service;

    @BeforeAll
    static void boot() throws Exception {
        // Role-gated declarations read the boot snapshot; this suite declares the
        // full-node set (all roles on) through a private settings file.
        File settingsDry = File.createTempFile("hohenheim-task-bootstrap", ".dry");
        settingsDry.delete();
        settingsDry.deleteOnExit();
        System.setProperty("hohenheim.settings", settingsDry.getAbsolutePath());
        HohenheimSettingsFiles.load();

        HohenheimEndpoints.init();
        // auto-discovery creates system_task + the M0xx tables
        TestDatabases.freshDatabase();

        service = TaskBootstrap.start(HohenheimDatabase.datasource());
    }

    /**
     * AIDEV-NOTE: the settings property is JVM-WIDE and the browser lane shares one fork,
     * so leaving it set pointed every later class in this fork at this suite's temp file --
     * SettingsGroupCoverageTest then read that path where it asserts the production one,
     * and failed on an order it never chose. Clearing it is half the restore; the reload is
     * what puts the loaded snapshot back.
     */
    @AfterAll
    static void shutdown() {
        if (service != null) service.shutdown();
        System.clearProperty("hohenheim.settings");
        HohenheimSettingsFiles.load();
    }

    @Test
    void everyDiscoveredTaskIsPinned() {
        assertThat(discoveredTypes())
            .as("every discovered Hohenheim ScheduledTask must be named in PINNED"
                + " -- a new task declares boot behaviour and a role gate, both reviewed here")
            .containsExactlyInAnyOrderElementsOf(
                PINNED.stream().map(Class::getName).toList());
    }

    @Test
    void everyDeclaredScheduleIsReconciledIntoSystemTask() {
        List<String> reconciled = service.taskModel().findAllSystemRows().stream()
            .map(row -> (String) row.get(SystemTaskModel.TYPE))
            .filter(type -> type != null && type.startsWith(TASK_PACKAGE))
            .distinct()
            .toList();

        assertThat(reconciled)
            .as("system_task must hold a row for exactly the tasks that declare a schedule")
            .containsExactlyInAnyOrderElementsOf(typesDeclaring(true));
    }

    @Test
    void bootAndCronDiscoveryTasksFireAtStartup() throws Exception {
        List<String> bootTypes = typesWithBootSchedule(true);
        assertThat(bootTypes)
            .as("a full-role node declares BOOT_AND_CRON tasks; an empty set would make"
                + " this whole test vacuous")
            .isNotEmpty();

        for (String type : bootTypes) {
            assertThat(awaitHistory(type))
                .as("BOOT_AND_CRON task should have a history row shortly after boot: " + type)
                .isTrue();
        }
    }

    @Test
    void tasksWithoutABootScheduleDoNotFireAtStartup() {
        List<String> quietTypes = typesWithBootSchedule(false);
        assertThat(quietTypes)
            .as("tasks that declare only cron schedules must exist for this test to mean anything")
            .isNotEmpty();

        for (String type : quietTypes) {
            assertThat(service.historyModel().findRecentForType(type, 5))
                .as("task declaring no BOOT_AND_CRON schedule must NOT fire at boot: " + type)
                .isEmpty();
        }
    }

    /** The Hohenheim half of the compile-time-discovered catalog. */
    private static List<TaskDescriptor> discovered() {
        return TaskCatalog.all().stream()
            .filter(descriptor -> descriptor.taskClass().getName().startsWith(TASK_PACKAGE))
            .toList();
    }

    private static List<String> discoveredTypes() {
        return discovered().stream().map(TaskDescriptor::typePath).toList();
    }

    /** @param declaring true for tasks declaring at least one schedule, false for the rest */
    private static List<String> typesDeclaring(boolean declaring) {
        return discovered().stream()
            .filter(descriptor -> descriptor.defaultSchedules().isEmpty() != declaring)
            .map(TaskDescriptor::typePath)
            .toList();
    }

    /**
     * Partition the declaring tasks by their OWN declaration, so the boot expectation is
     * whatever the task said and can never be a stale copy of it.
     *
     * @param withBoot true for tasks declaring a BOOT_AND_CRON schedule, false for the
     *                 declaring tasks that do not
     */
    private static List<String> typesWithBootSchedule(boolean withBoot) {
        return discovered().stream()
            .filter(descriptor -> !descriptor.defaultSchedules().isEmpty())
            .filter(descriptor -> descriptor.defaultSchedules().stream()
                .map(ScheduleDeclaration::kind)
                .anyMatch(kind -> kind == ScheduleKind.BOOT_AND_CRON) == withBoot)
            .map(TaskDescriptor::typePath)
            .toList();
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
