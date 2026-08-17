package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

/**
 * The backup lane's honesty budget, daemon-free: the first hermetic coverage of
 * {@code InstanceBackups} at all -- the snapshot lanes had {@code
 * InstanceSnapshotRetentionTest} and the defect it exists to catch (two captures in one
 * second sharing one payload name) sat unfixed in the backup lane precisely because no
 * test could catch it there.
 *
 * What this class CANNOT prove: nothing here crosses a network or touches a real
 * daemon, so ssh-target semantics, real transfer interruption and true thread-level
 * interleaving of two captures stay with the live suites; the "same second" here is two
 * sequential captures inside one wall-clock second, not two racing threads.
 *
 * AIDEV-NOTE: this class was @Tag("slow") until 2026-08-13 for ONE of its eight
 * journeys -- the retention-over-an-undeletable-artifact case, which needs a filesystem
 * that can refuse a delete and therefore a non-root process, a {@code LiveLane} need.
 * The slow tag is a CLASS property, so that one gate was keeping seven hermetic journeys
 * out of the default lane. It now lives in
 * {@link InstanceBackupRetentionRefusalTest}; both classes install the SAME
 * {@link BackupLaneFixture}. Anything added here that needs a live capability belongs
 * there instead -- moving the tag back would cost the default lane the other seven again.
 */
class InstanceBackupsTest {

    private static SqlDatasource datasource;
    private static int hostId;
    private static Path targetRoot;
    private static int targetId;
    private static BackupTarget target;

    @BeforeAll
    static void setUp() throws Exception {
        BackupLaneFixture fixture = BackupLaneFixture.install();
        datasource = fixture.datasource;
        hostId = fixture.hostId;
        targetRoot = fixture.targetRoot;
        targetId = fixture.targetId;
        target = fixture.target;
    }

    @AfterAll
    static void tearDown() {
        BackupLaneFixture.uninstall();
    }

    /**
     * THE defect the snapshot lanes fixed and this lane did not: two backups of one
     * instance started inside the same wall-clock second must not share a remote key,
     * because retention deleting the older ROW would then remove the artifact the
     * SURVIVING row still points at -- a prune that reports success while destroying a
     * live backup.
     */
    @Test
    void twoBackupsInOneSecondNeverShareARemoteKeyAndRetentionNeverEatsTheSurvivor() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int instanceId = instanceRecord("backup-same-second", hostId);
            service.deploy(instanceId);

            // 1. TWO captures inside one second (retention off so nothing prunes yet).
            //    The stamp resolves to the second, so aligning both inside one is what
            //    reproduces the collision; a pair that straddles a boundary is retried.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
            int older = 0;
            int newer = 0;
            boolean aligned = false;
            for (int attempt = 0; attempt < 5 && !aligned; attempt++) {
                long before = Instant.now().getEpochSecond();
                older = backups.backupNow(instanceId, targetId, target);
                newer = backups.backupNow(instanceId, targetId, target);
                aligned = Instant.now().getEpochSecond() == before;
                if (!aligned) {
                    backups.delete(older);
                    backups.delete(newer);
                }
            }
            if (!aligned) {
                fail("step 1: could not land two captures inside one second in 5 attempts;"
                    + " the collision window cannot be exercised on this machine");
            }

            String olderKey = remoteKeyOf(older);
            String newerKey = remoteKeyOf(newer);
            assertThat(newerKey)
                .as("step 1: two same-second captures of one instance must carry DISTINCT"
                    + " remote keys; a shared key makes the second upload silently"
                    + " overwrite the first artifact")
                .isNotEqualTo(olderKey);

            // 2. Retention of 1: the older row goes, the newer row STAYS -- and keeps a
            //    restorable artifact. Before the fix the shared key meant this delete
            //    removed the survivor's only payload while its row still read COMPLETE
            //    with a sha256.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 1);
            backups.pruneForRetention(instanceId);
            assertThat(Models.get(InstanceBackupModel.class).findById(older))
                .as("step 2: the older backup fell outside the window and its row is gone")
                .isNull();
            Row survivor = Models.get(InstanceBackupModel.class).findById(newer);
            assertThat(survivor)
                .as("step 2: the newer backup is inside the window and must survive")
                .isNotNull();
            assertThat((String) survivor.get(InstanceBackupModel.STATUS))
                .as("step 2: and it is COMPLETE").isEqualTo(InstanceBackupModel.STATUS_COMPLETE);
            try {
                assertThat(target.exists(newerKey))
                    .as("step 2: THE POINT -- the surviving row's artifact is still on the"
                        + " target after the prune; a COMPLETE row whose payload the prune"
                        + " destroyed is a backup that cannot be restored")
                    .isTrue();
                assertThat(target.storedSha256(newerKey))
                    .as("step 2: and it is byte-identical to what the row recorded")
                    .isEqualTo(survivor.get(InstanceBackupModel.SHA256));
            } catch (IOException unreadable) {
                fail("step 2: the surviving backup's artifact cannot be read: "
                    + unreadable.getMessage());
            }

            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    /**
     * The capability gate must fire BEFORE the backup target resolves: a view-only
     * tenant asking for a backup gets the instance tier's uniform refusal, never a
     * violation naming the instance's backup-target configuration -- the
     * capability-oracle rule {@code HohenheimAccess.requireOperationCapability}
     * documents.
     */
    @Test
    void aViewOnlyTenantGetsTheUniformRefusalNotTheTargetConfiguration() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int instanceId = instanceRecord("backup-gate-order", hostId);
            service.deploy(instanceId);
            // A DANGLING target pointer: resolving it throws a violation that NAMES the
            // configuration problem, which is operator information.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.BACKUP_TARGET_ID, 999_999)
                .updateAll();
            int viewerId = tenant("viewer@backup-gate.test", "Viewer");
            RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.VIEW, true);
            UserPrincipal viewer = new UserPrincipal(viewerId, "Viewer");

            // 1. The tenant with only `view` asks for a backup: refused with the SAME
            //    text any unauthorized instance act gets. Before the fix the refusal was
            //    backup_target_missing -- the target's admission state leaked to a
            //    caller who holds no backup authority.
            Throwable[] thrown = new Throwable[1];
            TenantConduits.as(viewer, () ->
                thrown[0] = catchThrowable(() -> backups.backupNow(instanceId)));
            assertThat(violationKeys(thrown[0]))
                .as("step 1: a view-only tenant gets the uniform instance refusal, not a"
                    + " violation naming the backup-target configuration")
                .isEqualTo("instance_not_permitted ");

            // 2. No row was written: the refusal really fired before the lane did
            //    anything, not after a failed attempt.
            assertThat(Models.get(InstanceBackupModel.class).find()
                    .where(InstanceBackupModel.INSTANCE_ID.eq(instanceId)).count())
                .as("step 2: the refused request left no backup row behind")
                .isZero();

            // 3. The OPERATOR (no request in flight) still gets the real named refusal:
            //    hoisting the gate must not swallow the configuration diagnosis for the
            //    caller entitled to it.
            assertThat(violationKeys(catchThrowable(() -> backups.backupNow(instanceId))))
                .as("step 3: the operator's refusal still names the missing target")
                .isEqualTo("backup_target_missing ");

            service.destroy(instanceId);
        });
    }

    /**
     * Restore-to-new is OPERATOR-ONLY: a tenant, even one holding {@code backups} on
     * the source instance, is refused by name -- restore creates an instance outside
     * the creation funnel, and every gate that funnel carries is tenant-facing.
     */
    @Test
    void restoreToNewRefusesTenantsByNameAndCreatesNothing() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int instanceId = instanceRecord("backup-operator-only", hostId);
            service.deploy(instanceId);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
            int backupId = backups.backupNow(instanceId, targetId, target);
            Row backup = Models.get(InstanceBackupModel.class).findById(backupId);

            int managerId = tenant("manager@backup-restore.test", "Manager");
            RecordGrants.grant(GrantSubjectType.USER, managerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimAccess.MANAGE, true);
            UserPrincipal manager = new UserPrincipal(managerId, "Manager");

            long instancesBefore = Models.get(InstanceModel.class).find().count();
            Throwable[] thrown = new Throwable[1];
            TenantConduits.as(manager, () -> thrown[0] = catchThrowable(() ->
                backups.restoreToNew(backup, target, "not-yours", null)));
            assertThat(violationKeys(thrown[0]))
                .as("step 1: the tenant restore is refused by name")
                .isEqualTo("backup_restore_operator_only ");
            assertThat(Models.get(InstanceModel.class).find().count())
                .as("step 2: and no instance record was created by the refused attempt")
                .isEqualTo(instancesBefore);

            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    /**
     * The explicit-target flow (null target id: tests, future re-target flows) must not
     * produce rows retention can never remove: re-resolving a null TARGET_ID throws, so
     * the sweep run by the completing backup uses the target IT HOLDS for rows written
     * to that same target. Before this, such a row was kept forever ("kept for a later
     * sweep", every sweep) while its artifact stayed on the target.
     */
    @Test
    void retentionCanPruneRowsWhoseTargetIdCannotBeReResolved() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int instanceId = instanceRecord("backup-null-target", hostId);
            service.deploy(instanceId);

            // 1. Two explicit-target backups with NO target record id; the second one's
            //    completion sweep (retention 1) must remove the first, row AND artifact.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 1);
            int older = backups.backupNow(instanceId, null, target);
            String olderKey = remoteKeyOf(older);
            int newer = backups.backupNow(instanceId, null, target);
            assertThat(Map.of(
                    "olderRow", String.valueOf(
                        Models.get(InstanceBackupModel.class).findById(older) != null),
                    "olderArtifact", String.valueOf(
                        Files.exists(targetRoot.resolve(olderKey)))))
                .as("step 1: the in-hand target prunes the same-lane row a null target id"
                    + " would otherwise strand forever")
                .isEqualTo(Map.of("olderRow", "false", "olderArtifact", "false"));
            assertThat(Models.get(InstanceBackupModel.class).findById(newer))
                .as("step 1: the completing backup itself survives").isNotNull();

            HohenheimSettings.VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    /**
     * Boot settle of interrupted uploads: a row left {@code uploading} by a DEAD
     * controller becomes FAILED and its possibly-committed artifact is removed; a row a
     * LIVE upload is writing right now is never touched (the process-start fence); an
     * unreachable target keeps the key named in the error instead of losing it.
     */
    @Test
    void bootSettleFailsDeadUploadsRemovesTheirArtifactsAndLeavesLiveUploadsAlone() throws IOException {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            int instanceId = instanceRecord("backup-boot-settle", hostId);
            service.deploy(instanceId);
            Instant beforeThisProcess = Instant.ofEpochMilli(
                java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime())
                .minusSeconds(3600);

            // 1. THREE uploading rows: a dead one whose artifact committed, a dead one
            //    whose target cannot be resolved, and a LIVE one (written "now", i.e.
            //    by this very process).
            String deadKey = ControllerIdentity.token() + "/instance-" + instanceId
                + "/test-dead-upload.hib";
            int dead = uploadingRow(instanceId, targetId, deadKey, beforeThisProcess);
            try {
                Files.createDirectories(targetRoot.resolve(deadKey).getParent());
                Files.writeString(targetRoot.resolve(deadKey), "committed-mid-crash");
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            String orphanKey = ControllerIdentity.token() + "/instance-" + instanceId
                + "/test-orphan-upload.hib";
            int orphan = uploadingRow(instanceId, null, orphanKey, beforeThisProcess);
            int live = uploadingRow(instanceId, targetId, ControllerIdentity.token()
                + "/instance-" + instanceId + "/test-live-upload.hib", null);

            // 2. The settle.
            InstanceBackups.recoverInterrupted();

            Row deadRow = Models.get(InstanceBackupModel.class).findById(dead);
            assertThat(Map.of(
                    "status", String.valueOf((Object) deadRow.get(InstanceBackupModel.STATUS)),
                    "artifact", String.valueOf(Files.exists(targetRoot.resolve(deadKey)))))
                .as("step 2: the dead upload is FAILED and its half-orphaned artifact is"
                    + " gone from the target -- it was never verified, so nothing may"
                    + " ever restore from it")
                .isEqualTo(Map.of("status", InstanceBackupModel.STATUS_FAILED,
                    "artifact", "false"));

            Row orphanRow = Models.get(InstanceBackupModel.class).findById(orphan);
            assertThat(String.valueOf((Object) orphanRow.get(InstanceBackupModel.STATUS)))
                .as("step 2: the unresolvable-target row is FAILED too, not stuck forever")
                .isEqualTo(InstanceBackupModel.STATUS_FAILED);
            assertThat(String.valueOf((Object) orphanRow.get(InstanceBackupModel.ERROR)))
                .as("step 2: and its error NAMES the possibly-surviving key -- the"
                    + " evidence must not lose the pointer")
                .contains(orphanKey);

            assertThat((String) Models.get(InstanceBackupModel.class).findById(live)
                    .get(InstanceBackupModel.STATUS))
                .as("step 2: the row written by THIS process is a live upload and stays"
                    + " untouched -- settling it would destroy a backup in flight")
                .isEqualTo(InstanceBackupModel.STATUS_UPLOADING);

            service.destroy(instanceId);
        });
    }

    /**
     * Deleting a backup target used to succeed while COMPLETE rows still pointed at it,
     * so the restore failed at USE ({@code backup_target_missing}) instead of the delete
     * failing at the point of decision. The guard refuses -- a COMPLETE row is the
     * user's recovery asset and a config-page delete must never silently strand it.
     */
    @Test
    void deletingABackupTargetRefusesWhileAnythingStillPointsAtIt() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            Row guard = Models.get(BackupTargetModel.class).createEmptyRow();
            guard.set(BackupTargetModel.NAME, "delete-guard-target");
            guard.set(BackupTargetModel.KIND, "hohenheim:filesystem");
            guard.set(BackupTargetModel.SETTINGS,
                Map.of("path", targetRoot.toAbsolutePath().toString()));
            Models.get(BackupTargetModel.class).save(guard);
            int guardTargetId = guard.get(BackupTargetModel.ID);
            int instanceId = instanceRecord("target-delete-guard", hostId);
            service.deploy(instanceId);

            // 1. A COMPLETE backup lands on the target.
            int backupId = backups.backupNow(instanceId, guardTargetId, target);
            assertThat((String) Models.get(InstanceBackupModel.class).findById(backupId)
                    .get(InstanceBackupModel.STATUS))
                .as("step 1: the backup completed onto the guarded target")
                .isEqualTo(InstanceBackupModel.STATUS_COMPLETE);

            // 2. THE REFUSAL, at the point of decision: the delete names what still
            //    depends on the target, and both rows survive.
            assertThat(violationKeys(catchThrowable(() ->
                    Models.get(BackupTargetModel.class).delete((Object) guardTargetId))))
                .as("step 2: deleting a target with a COMPLETE backup refuses by name")
                .isEqualTo("backup_target_in_use ");
            assertThat(Models.get(BackupTargetModel.class).findById(guardTargetId))
                .as("step 2: the refused delete left the target row in place")
                .isNotNull();

            // 3. The backup row is gone, but a live INSTANCE still declares the target
            //    as its destination: still refused.
            backups.delete(backupId);
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.BACKUP_TARGET_ID, guardTargetId)
                .updateAll();
            assertThat(violationKeys(catchThrowable(() ->
                    Models.get(BackupTargetModel.class).delete((Object) guardTargetId))))
                .as("step 3: a target an instance backs up to refuses deletion too")
                .isEqualTo("backup_target_in_use ");

            // 4. The CONTROL-PLANE setting names targets by NAME, not id -- an
            //    id-column-only guard would miss it (the third reference lane).
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.BACKUP_TARGET_ID, (Object) null)
                .updateAll();
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET,
                "delete-guard-target");
            try {
                assertThat(violationKeys(catchThrowable(() ->
                        Models.get(BackupTargetModel.class).delete((Object) guardTargetId))))
                    .as("step 4: the control-plane destination refuses with its own name")
                    .isEqualTo("backup_target_control_plane ");
            } finally {
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET, null);
            }

            // 5. FAILED rows deliberately do NOT block: they are evidence, and counting
            //    them would make a target with only junk rows undeletable for nothing.
            int junk = uploadingRow(instanceId, guardTargetId, null, null);
            Models.get(InstanceBackupModel.class).find()
                .where(InstanceBackupModel.ID.eq(junk))
                .assign(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED)
                .updateAll();
            Models.get(BackupTargetModel.class).delete((Object) guardTargetId);
            assertThat(Models.get(BackupTargetModel.class).findById(guardTargetId))
                .as("step 5: with only a FAILED row left, the delete goes through")
                .isNull();

            // 6. THE STRAND THE GUARD PREVENTS, shown on the other side: a row whose
            //    target is genuinely gone fails at use, by name -- this is what used to
            //    happen to COMPLETE rows.
            assertThat(violationKeys(catchThrowable(() ->
                    BackupTargetKinds.targetFor(guardTargetId))))
                .as("step 6: resolving the deleted target fails at use, which is exactly"
                    + " the late failure the refusal moves to the point of decision")
                .isEqualTo("backup_target_missing ");

            Models.get(InstanceBackupModel.class).find()
                .where(InstanceBackupModel.ID.eq(junk)).delete();
            service.destroy(instanceId);
        });
    }

    /**
     * A controller killed mid-capture or mid-restore used to BRICK the instance:
     * {@code capturing}/{@code restoring} are cleared only by the in-process outcome
     * paths, so deploy, stop, backup and snapshot refused forever and destroy was the
     * only escape. The boot settle rides the same process-start fence as the backup and
     * snapshot settles: a status stamped before this process existed is a corpse, one
     * this process stamped is a live operation and untouchable.
     */
    @Test
    void bootSettleRecoversInstancesADeadControllerLeftCapturingOrRestoring() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            Instant deadStamp = Instant.ofEpochMilli(
                ManagementFactory.getRuntimeMXBean().getStartTime()).minusSeconds(3600);

            // 1. THE BRICK, reproduced: a dead controller's CAPTURING stamp makes every
            //    operation refuse by name, and nothing in the product ever cleared it.
            int bricked = instanceRecord("settle-dead-capture", hostId);
            service.deploy(bricked);
            stampStatusAt(bricked, InstanceModel.STATUS_CAPTURING, deadStamp);
            assertThat(violationKeys(catchThrowable(() -> service.deploy(bricked))))
                .as("step 1: deploy refuses the dead controller's capture stamp by name")
                .isEqualTo("instance_busy ");
            assertThat(violationKeys(catchThrowable(() -> service.stop(bricked))))
                .as("step 1: stop refuses it too")
                .isEqualTo("instance_busy ");
            assertThat(violationKeys(catchThrowable(() ->
                    backups.backupNow(bricked, targetId, target))))
                .as("step 1: and so does a new backup -- the brick, verbatim")
                .isEqualTo("instance_busy ");

            // 2. A capture THIS process is running right now: same status, live stamp.
            int liveCapture = instanceRecord("settle-live-capture", hostId);
            service.deploy(liveCapture);
            stampStatusAt(liveCapture, InstanceModel.STATUS_CAPTURING, null);

            // 3. A dead controller's interrupted RESTORE: the payload may be
            //    half-written, so no daemon answer can vouch for it.
            int restoring = instanceRecord("settle-dead-restore", hostId);
            service.deploy(restoring);
            stampStatusAt(restoring, InstanceModel.STATUS_RESTORING, deadStamp);

            // 4. The boot settle (what ServerMain runs at every INSTANCES-role boot).
            InstanceService.recoverInterrupted();

            // 5. The bricked one settled from DAEMON truth: the workload kept running
            //    through the killed native-lane capture, so the record says running.
            assertThat(statusOf(bricked))
                .as("step 5: a dead capture settles to what the daemon reports (running)")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 6. And it is OPERABLE again, proven end-to-end: a real backup runs.
            int recovered = backups.backupNow(bricked, targetId, target);
            assertThat((String) Models.get(InstanceBackupModel.class).findById(recovered)
                    .get(InstanceBackupModel.STATUS))
                .as("step 6: the recovered instance completes a whole new backup")
                .isEqualTo(InstanceBackupModel.STATUS_COMPLETE);

            // 7. The LIVE capture is untouched -- settling it would let a rival
            //    operation loose on data the running capture is reading.
            assertThat(statusOf(liveCapture))
                .as("step 7: a capture stamped by THIS process stays protected")
                .isEqualTo(InstanceModel.STATUS_CAPTURING);
            assertThat(violationKeys(catchThrowable(() -> service.deploy(liveCapture))))
                .as("step 7: and still refuses rival operations")
                .isEqualTo("instance_busy ");

            // 8. The interrupted restore settles to ERROR: operable (an operator can
            //    re-restore or redeploy), but loudly not "fine".
            assertThat(statusOf(restoring))
                .as("step 8: a dead restore settles to error, never to a clean status")
                .isEqualTo(InstanceModel.STATUS_ERROR);

            service.destroy(bricked);
            service.destroy(liveCapture);
            service.destroy(restoring);
        });
    }

    /** The instance row's current status. */
    private static String statusOf(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId)
            .get(InstanceModel.STATUS);
    }

    /**
     * Stamp a status the way a controller would, back-dated to {@code writtenAt} --
     * null keeps a fresh stamp (a live operation's write).
     */
    private static void stampStatusAt(int instanceId, String status, Instant writtenAt) {
        if (writtenAt == null) {
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.STATUS, status)
                .assign(InstanceModel.UPDATED_AT, Instant.now())
                .updateAll();
            return;
        }
        Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.STATUS, status)
            .assign(InstanceModel.CREATED_AT, writtenAt)
            .assign(InstanceModel.UPDATED_AT, writtenAt)
            .updateAll();
    }

    /** An {@code uploading} row; {@code writtenAt} null keeps the save-time stamps (a live row). */
    private static int uploadingRow(int instanceId, Integer rowTargetId, String key,
                                    Instant writtenAt) {
        Row row = Models.get(InstanceBackupModel.class).createEmptyRow();
        row.set(InstanceBackupModel.INSTANCE_ID, instanceId);
        row.set(InstanceBackupModel.TARGET_ID, rowTargetId);
        row.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_UPLOADING);
        row.set(InstanceBackupModel.REMOTE_KEY, key);
        Models.get(InstanceBackupModel.class).save(row);
        int id = row.get(InstanceBackupModel.ID);
        if (writtenAt != null) {
            Models.get(InstanceBackupModel.class).find()
                .where(InstanceBackupModel.ID.eq(id))
                .assign(InstanceBackupModel.CREATED_AT, writtenAt)
                .assign(InstanceBackupModel.UPDATED_AT, writtenAt)
                .updateAll();
        }
        return id;
    }

    // -- fixtures -------------------------------------------------------------

    private static String remoteKeyOf(int backupId) {
        return BackupLaneFixture.remoteKeyOf(backupId);
    }

    private static String violationKeys(Throwable thrown) {
        assertThat(thrown).as("a Violations refusal was thrown").isInstanceOf(Violations.class);
        StringBuilder keys = new StringBuilder();
        for (var violation : ((Violations) thrown).all()) {
            keys.append(violation.message().key()).append(' ');
        }
        return keys.toString();
    }

    private static int tenant(String email, String name) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, name);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        return user.get(UserModel.ID);
    }

    private static int instanceRecord(String name, int serverId) {
        return BackupLaneFixture.instanceRecord(name, serverId);
    }
}
