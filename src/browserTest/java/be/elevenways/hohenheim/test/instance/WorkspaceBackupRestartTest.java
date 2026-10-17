package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A backup of a source-deploying workspace restarts the runtime it stopped, never a new build of its source.
 *
 * AIDEV-NOTE: the defect seen on starfleet (plan section 68): the cold lane's restart went through
 * {@code InstanceService.deploy}, which folds a workspace with a repository into a checkout and build, so a
 * workspace whose current source does not build could never be backed up. Only the daemon is fake.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class WorkspaceBackupRestartTest {

    private static BackupLaneFixture fixture;
    private static FakeWorkspaceDaemon daemon;
    private static Integer savedUidBase;

    @BeforeAll
    static void setUp() throws Exception {
        fixture = BackupLaneFixture.install();
        savedUidBase = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.VOLUME_UID_BASE);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE, 200000);
        daemon = FakeWorkspaceDaemon.install();
    }

    @AfterAll
    static void tearDown() {
        FakeWorkspaceDaemon.uninstall();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE, savedUidBase);
        BackupLaneFixture.uninstall();
    }

    @Test
    void aWorkspaceWhoseSourceNoLongerBuildsIsBackedUpFromItsLastBuiltRuntime() {
        Db.run(fixture.datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int id = FakeWorkspaceDaemon.sourceWorkspace("backup-unbuildable", fixture.hostId, "npm run build");
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);

            // 1. The source builds once: the workspace runs what that build left.
            new WorkspaceBuilds(service).deploy(id, null, null, DeployTrigger.MANUAL);
            assertThat(daemon.isRunning(handle))
                .as("step 1: the first deploy leaves the workspace running")
                .isTrue();

            // 2. The source changes and no longer builds: a deploy fails, the old runtime keeps serving.
            daemon.buildExitCode = 1;
            assertThat(catchThrowable(() -> new WorkspaceBuilds(service)
                .deploy(id, null, null, DeployTrigger.MANUAL)))
                .as("step 2: the current source does not build")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("workspace_build_failed");
            assertThat(daemon.isRunning(handle))
                .as("step 2: the last built runtime still runs")
                .isTrue();
            int execsBefore = daemon.execs.size();
            long buildsBefore = buildOperations(id);

            // 3. Back up now: stopped for the capture, then the SAME runtime comes back, no checkout and no build.
            int backupId = backups.backupNow(id, fixture.targetId, fixture.target);
            Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
            assertThat((String) backup.get(InstanceBackupModel.STATUS))
                .as("step 3: the backup completes although the source does not build")
                .isEqualTo(InstanceBackupModel.STATUS_COMPLETE);
            assertThat(daemon.events)
                .as("step 3: the capture ran while the workload was stopped")
                .contains("capture:" + handle);
            assertThat(daemon.isRunning(handle))
                .as("step 3: and the workspace runs again afterwards")
                .isTrue();
            assertThat(daemon.execs.size())
                .as("step 3: the restart ran no checkout and no build inside the workspace")
                .isEqualTo(execsBefore);
            assertThat(buildOperations(id))
                .as("step 3: and recorded no deploy operation")
                .isEqualTo(buildsBefore);

            // 4. The restart itself fails: the backup fails with the restart's own reason on its row ...
            daemon.failStart = true;
            assertThat(catchThrowable(() -> backups.backupNow(id, fixture.targetId, fixture.target)))
                .as("step 4: a backup whose restart fails is refused")
                .isInstanceOf(Violations.class);
            daemon.failStart = false;
            String restartPrefix = "The workload could not be started again after the capture: ";
            List<String> errors = Models.get(InstanceBackupModel.class).find()
                .where(InstanceBackupModel.INSTANCE_ID.eq(id))
                .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_FAILED))
                .all().stream()
                .map(row -> String.valueOf((Object) row.get(InstanceBackupModel.ERROR)))
                .toList();
            assertThat(errors)
                .as("step 4: the failed row names the restart and its reason")
                .singleElement()
                .satisfies(error -> assertThat(error)
                    .startsWith(restartPrefix)
                    .contains("scripted start failure"));

            // 5. ... and in the history, where a scheduled backup's failure is read.
            assertThat(RecordedChanges.withFact(Models.get(InstanceModel.class), id,
                    HohenheimActivityAction.BACKUP_FAILED).stream()
                .map(recorded -> String.valueOf(recorded.fact(HohenheimActivityAction.BACKUP_FAILED)
                    .get(HohenheimActivityAction.REASON_KEY))))
                .as("step 5: the history names the failed restart with its reason")
                .anyMatch(reason -> reason.contains("could not be started again after the capture (")
                    && reason.contains("scripted start failure"));
        });
    }

    private static long buildOperations(int instanceId) {
        return Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(instanceId))
            .count();
    }
}
