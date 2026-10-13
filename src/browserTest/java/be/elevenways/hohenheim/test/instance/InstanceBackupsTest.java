package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
            int older = 0;
            int newer = 0;
            boolean aligned = false;
            for (int attempt = 0; attempt < 5 && !aligned; attempt++) {
                long before = Now.instant().getEpochSecond();
                older = backups.backupNow(instanceId, targetId, target);
                newer = backups.backupNow(instanceId, targetId, target);
                aligned = Now.instant().getEpochSecond() == before;
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
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 1);
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

            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
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
            // A MISCONFIGURED target (no directory): resolving it throws a violation that
            // NAMES the target and its configuration problem, which is operator information.
            // (A dangling pointer used to stand in here; backup_target_id is an enforced
            // foreign key now, so the reachable broken target is a real, unusable one.)
            Row broken = Models.get(BackupTargetModel.class).createEmptyRow();
            broken.set(BackupTargetModel.NAME, "backup-gate-misconfigured");
            broken.set(BackupTargetModel.KIND, "hohenheim:filesystem");
            broken.set(BackupTargetModel.SETTINGS, Map.of());
            Models.get(BackupTargetModel.class).save(broken);
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.BACKUP_TARGET_ID, broken.get(BackupTargetModel.ID))
                .updateAll();
            int viewerId = ApiSupport.user("viewer@backup-gate.test", "Viewer");
            RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.VIEW, true);
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
                .as("step 3: the operator's refusal still names the broken target")
                .isEqualTo("backup_target_invalid ");

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
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
            int backupId = backups.backupNow(instanceId, targetId, target);
            Row backup = Models.get(InstanceBackupModel.class).findById(backupId);

            int managerId = ApiSupport.user("manager@backup-restore.test", "Manager");
            RecordGrants.grant(GrantSubjectType.USER, managerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.MANAGE, true);
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

            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
            service.destroy(instanceId);
        });
    }

    /**
     * Restore ELSEWHERE, the replacement for HA after a dead host: the restore takes a host pick whose options are the
     * placement gate's own verdicts, its blank lets placement pick, and none of it touches the silent source host.
     */
    @Test
    void restoreToNewTakesAHostPickAndLandsElsewhereWhenTheSourceHostIsDead() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int instanceId = instanceRecord("backup-elsewhere", hostId);
            service.deploy(instanceId);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
            int backupId = backups.backupNow(instanceId, targetId, target);
            Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
            int spareId = HostFixtures.admittedIncusHost("backup-spare-host");
            Integer contactBound = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Hosts.CONTACT_MAX_AGE_MINUTES);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Hosts.CONTACT_MAX_AGE_MINUTES, 60);
            stampLastSeen(hostId, Now.instant().minus(Duration.ofDays(1)));
            List<Integer> restored = new ArrayList<>();
            try {
                // 1. The source host has gone silent. The restore's host pick offers the spare and shows the source
                //    disabled with the placement gate's own refusal, the words the Migrate page shows beside a host.
                FormSpec form = OperationPipeline.inputForm(restoreRequest(backup));
                FormEntry pick = form.entries().stream().filter(entry -> entry.name().equals("serverId"))
                    .findFirst().orElse(null);
                assertThat(pick).as("step 1: the restore asks for a host").isInstanceOf(Select.class);
                List<? extends FieldOption<?>> options = ((Select<?>) pick).options()
                    .resolve(EditContext.of(TenantConduits.operator()));
                assertThat(options).as("step 1: the dead source is shown, disabled, with the gate's refusal")
                    .anySatisfy(option -> {
                        assertThat(option.value()).isEqualTo(hostId);
                        assertThat(option.disabled()).isTrue();
                        assertThat(option.description().key()).isEqualTo("host_contact_lapsed");
                    });
                assertThat(options).as("step 1: the spare host is offered")
                    .anySatisfy(option -> {
                        assertThat(option.value()).isEqualTo(spareId);
                        assertThat(option.disabled()).isFalse();
                    });

                // 2. Naming the dead source is refused by name, and creates nothing.
                long instancesBefore = Models.get(InstanceModel.class).find().count();
                assertThat(violationKeys(catchThrowable(() -> backups.restoreToNew(backupId, "to-the-dead", hostId))))
                    .as("step 2: a restore onto the silent source is refused by the placement gate")
                    .isEqualTo("host_contact_lapsed ");
                assertThat(violationKeys(catchThrowable(() -> OperationPipeline.invoke(restoreRequest(backup)
                        .form(Map.of("serverId", hostId))))))
                    .as("step 2: and the form refuses the disabled host with the same named refusal, never the "
                        + "select's generic one").isEqualTo("host_contact_lapsed ");
                assertThat(Models.get(InstanceModel.class).find().count())
                    .as("step 2: no instance record was created").isEqualTo(instancesBefore);

                // 3. Left blank, placement picks: the restore lands on the spare host. The spare holds no copy of the
                //    image a NEW instance of this kind would be built from, which refuses a create there, but a
                //    restore imports the whole instance and never asks for the image.
                FakeNativeDaemons.FakeNativeKind.IMAGE_MISSING_ON.add("backup-spare-host");
                assertThat(violationKeys(catchThrowable(() -> InstancePlacement.chooseForBucket(
                        InstanceQuota.creationBucket(), InstancePlacement.Workload.of(
                            InstanceKinds.getHandler(FakeNativeDaemons.FakeNativeKind.ID.toString()), Map.of()),
                        null))))
                    .as("step 3: a new instance of the kind is not placed where its image is missing")
                    .isEqualTo("host_prepared_image_missing ");
                InstanceBackupOperations.Restored picked = (InstanceBackupOperations.Restored) OperationPipeline
                    .invoke(restoreRequest(backup).form(Map.of())).value();
                restored.add(picked.instanceId());
                assertThat((Integer) Models.get(InstanceModel.class).findById(picked.instanceId())
                        .get(InstanceModel.SERVER_ID))
                    .as("step 3: picked for you lands on the host that accepts it, not the dead source")
                    .isEqualTo(spareId);

                // 4. Named explicitly through the form, the spare is honoured.
                InstanceBackupOperations.Restored named = (InstanceBackupOperations.Restored) OperationPipeline
                    .invoke(restoreRequest(backup).form(Map.of("serverId", spareId))).value();
                restored.add(named.instanceId());
                assertThat((Integer) Models.get(InstanceModel.class).findById(named.instanceId())
                        .get(InstanceModel.SERVER_ID))
                    .as("step 4: the picked host is where the restore lands").isEqualTo(spareId);
            } finally {
                FakeNativeDaemons.FakeNativeKind.IMAGE_MISSING_ON.clear();
                stampLastSeen(hostId, Now.instant());
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Hosts.CONTACT_MAX_AGE_MINUTES, contactBound);
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
                restored.forEach(service::destroy);
                service.destroy(instanceId);
                Models.get(ServerModel.class).delete(spareId);
            }
        });
    }

    /**
     * A plain backup carries the instance's declared host-directory volumes with their contents, and a restore
     * declares them on the new record and lands them on the target host's volume storage, counted in the capacity
     * check before anything is created.
     */
    @Test
    void aPlainBackupCarriesHostVolumesAndARestoreLandsThemOnTheTargetHost() throws Exception {
        FakeHostVolumes storage = FakeHostVolumes.install();
        try {
            Db.run(datasource, () -> {
                InstanceService service = new InstanceService();
                InstanceBackups backups = new InstanceBackups();
                int instanceId = instanceRecord("backup-host-volumes", hostId);
                service.deploy(instanceId);
                String hostName = ServerModel.nameOf(hostId);
                InstanceVolumes.declare(instanceId, "home", "/home/dev", 512L * 1024 * 1024, true);
                InstanceVolumes.mountsFor(instanceId, hostName, null);
                Path sourceHome = storage.directoryOf(hostName, InstanceVolumes.hostPathFor(instanceId, "home"));
                int spareId = HostFixtures.admittedIncusHost("backup-volume-spare");
                List<Integer> restored = new ArrayList<>();
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
                try {
                    try {
                        Files.writeString(sourceHome.resolve("notes.txt"), "kept in home");
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }

                    // 1. The backup names the host volume it carries.
                    int backupId = backups.backupNow(instanceId, targetId, target);
                    Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
                    assertThat(List.<Object>copyOf(RawValues.list(RawValues.map((Object) backup.get(
                            InstanceBackupModel.SUMMARY)).get(InstanceBackupModel.SUMMARY_HOST_VOLUMES))))
                        .as("step 1: the backup carries the declared host volume").containsExactly("home");

                    // 2. A target host whose volume storage cannot hold it refuses by name and creates nothing.
                    long instancesBefore = Models.get(InstanceModel.class).find().count();
                    FakeHostVolumes.availableBytes = 1;
                    assertThat(violationKeys(catchThrowable(() -> backups.restoreToNew(backupId, "hv-full", spareId))))
                        .as("step 2: the host volumes count in the restore's capacity check")
                        .isEqualTo("restore_capacity ");
                    assertThat(Models.get(InstanceModel.class).find().count())
                        .as("step 2: no instance record was created").isEqualTo(instancesBefore);
                    FakeHostVolumes.availableBytes = Long.MAX_VALUE / 4;

                    // 3. Restored elsewhere, the new record declares the volume and its host holds the contents.
                    int newId = backups.restoreToNew(backupId, "hv-restored", spareId).instanceId();
                    restored.add(newId);
                    List<Row> declared = InstanceVolumes.declaredFor(newId);
                    assertThat(declared).as("step 3: the restored record declares the host volume").hasSize(1);
                    assertThat((String) declared.get(0).get(be.elevenways.hohenheim.model.InstanceVolumeModel.CONTAINER_PATH))
                        .as("step 3: at its captured path").isEqualTo("/home/dev");
                    assertThat(storage.directoryOf("backup-volume-spare", InstanceVolumes.hostPathFor(newId, "home"))
                            .resolve("notes.txt"))
                        .as("step 3: the target host's volume storage holds the captured contents")
                        .hasContent("kept in home");
                    assertThat(sourceHome.resolve("notes.txt"))
                        .as("step 3: the source keeps its own copy").hasContent("kept in home");
                } finally {
                    FakeHostVolumes.availableBytes = Long.MAX_VALUE / 4;
                    Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
                    restored.forEach(service::destroy);
                    service.destroy(instanceId);
                    Models.get(ServerModel.class).delete(spareId);
                }
            });
        } finally {
            FakeHostVolumes.uninstall();
        }
    }

    /**
     * A machine backed up with install media in its drive imports only where that ISO exists, so the backup names its
     * media and a host that lacks them is refused by name, in the pick and in the write; without the media the same
     * host takes the restore.
     */
    @Test
    void aBackupWithInstallMediaRestoresOnlyWhereTheMediaAre() throws Exception {
        try (FakeIncusSocket holding = FakeIncusSocket.start(); FakeIncusSocket lacking = FakeIncusSocket.start()) {
            holding.daemon.customVolumes.put("alpine.iso", Map.of("name", "alpine.iso", "content_type", "iso"));
            Db.run(datasource, () -> {
                InstanceService service = new InstanceService();
                InstanceBackups backups = new InstanceBackups();
                int instanceId = instanceRecord("backup-with-media", hostId);
                service.deploy(instanceId);
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
                Row drive = Models.get(InstanceDeviceModel.class).createEmptyRow();
                drive.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
                drive.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_CDROM);
                drive.set(InstanceDeviceModel.NAME, "cdrom2");
                drive.set(InstanceDeviceModel.SOURCE_MEDIA, "alpine.iso");
                Models.get(InstanceDeviceModel.class).save(drive);
                // The workload holds the drive too, under a name no derivation would pick: an import keeps it.
                FakeNativeDaemons.daemonOf(hostId).get(FakeNativeDaemons.handleOf(instanceId)).cdroms
                    .put("cdrom2", "alpine.iso");
                List<Integer> restored = new ArrayList<>();
                int holdingId = HostFixtures.admittedIncusHost("media-holding-host");
                int lackingId = HostFixtures.admittedIncusHost("media-lacking-host");
                Models.get(ServerModel.class).find().where(ServerModel.ID.eq(holdingId))
                    .assign(ServerModel.INCUS_URL, holding.url()).updateAll();
                Models.get(ServerModel.class).find().where(ServerModel.ID.eq(lackingId))
                    .assign(ServerModel.INCUS_URL, lacking.url()).updateAll();
                try {
                    // 1. The backup names the ISO in the machine's drive on its row.
                    int withMedia = backups.backupNow(instanceId, targetId, target);
                    Row backup = Models.get(InstanceBackupModel.class).findById(withMedia);
                    assertThat(RawValues.map((Object) backup.get(InstanceBackupModel.SUMMARY)).get("install_media"))
                        .as("step 1: the backup names its install media").isEqualTo(List.of("alpine.iso"));

                    // 2. The restore pick offers the host holding the ISO and refuses the other by name, saying what
                    //    to do about it.
                    Map<Integer, InstanceBackups.Destination> picks = destinationsById(backup);
                    assertThat(picks.get(holdingId).refusal()).as("step 2: the host holding the ISO is offered")
                        .isNull();
                    assertThat(picks.get(lackingId).refusal()).as("step 2: the host without it is refused")
                        .isNotNull().extracting(Microcopy::key).isEqualTo("restore_install_media_missing");
                    assertThat(ApiSupport.shippedText(picks.get(lackingId).refusal()))
                        .as("step 2: naming the ISO and the way out")
                        .contains("alpine.iso").contains("media-lacking-host").contains("Detach");

                    // 3. The write refuses the same host with the same words, and creates nothing.
                    long instancesBefore = Models.get(InstanceModel.class).find().count();
                    assertThat(violationKeys(catchThrowable(() -> backups.restoreToNew(withMedia, "media-copy",
                            lackingId))))
                        .as("step 3: the restore onto the host without the ISO is refused by name")
                        .isEqualTo("restore_install_media_missing ");
                    assertThat(Models.get(InstanceModel.class).find().count())
                        .as("step 3: no instance record was created").isEqualTo(instancesBefore);

                    // 4. Onto the host that holds the ISO the restore imports the machine WITH its drive, and the new
                    //    record has the device row for it under the name the daemon holds it, so its Devices tab
                    //    shows what the machine carries and the deploy's reconcile adds no second drive.
                    int copyId = backups.restoreToNew(withMedia, "media-copy", holdingId).instanceId();
                    restored.add(copyId);
                    assertThat(Models.get(InstanceDeviceModel.class).find()
                            .where(InstanceDeviceModel.INSTANCE_ID.eq(copyId)).all())
                        .as("step 4: the restored record has the drive's row").singleElement().satisfies(row -> {
                            assertThat((String) row.get(InstanceDeviceModel.NAME)).isEqualTo("cdrom2");
                            assertThat((String) row.get(InstanceDeviceModel.TYPE))
                                .isEqualTo(InstanceDeviceModel.TYPE_CDROM);
                            assertThat((String) row.get(InstanceDeviceModel.SOURCE_MEDIA)).isEqualTo("alpine.iso");
                        });
                    assertThat(FakeNativeDaemons.daemonOf(holdingId).get(FakeNativeDaemons.handleOf(copyId)).cdroms)
                        .as("step 4: and the restored machine holds that one drive").containsExactly(
                            Map.entry("cdrom2", "alpine.iso"));

                    // 5. With the ISO out of the drive, a new backup names no media and that host takes it.
                    Models.get(InstanceDeviceModel.class).delete(drive.get(InstanceDeviceModel.ID));
                    FakeNativeDaemons.daemonOf(hostId).get(FakeNativeDaemons.handleOf(instanceId)).cdroms.clear();
                    Row without = Models.get(InstanceBackupModel.class)
                        .findById(backups.backupNow(instanceId, targetId, target));
                    assertThat(RawValues.map((Object) without.get(InstanceBackupModel.SUMMARY)).get("install_media"))
                        .as("step 5: a backup without media names none").isEqualTo(List.of());
                    assertThat(destinationsById(without).get(lackingId).refusal())
                        .as("step 5: so the host without the ISO is offered").isNull();
                } finally {
                    Models.get(InstanceDeviceModel.class).find().where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId))
                        .all().forEach(row -> Models.get(InstanceDeviceModel.class).delete(row.get(InstanceDeviceModel.ID)));
                    Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
                    restored.forEach(service::destroy);
                    service.destroy(instanceId);
                    Models.get(ServerModel.class).delete(holdingId);
                    Models.get(ServerModel.class).delete(lackingId);
                }
            });
        }
    }

    /**
     * An imported export brings every device its definition carries: an extra network interface gets its device row
     * on the new record from the same read that lands install media, and a data disk comes back with its contents on
     * a volume of the new record's own naming, counted at its size by the capacity check. A backup an older build
     * wrote (its export names the disk, nothing carries the volume) still holds the restore in error by name.
     */
    @Test
    void aRestoredMachineGetsEachDeviceItsExportCarriesAndItsDataDisksWithTheirContents() throws Exception {
        try (FakeIncusSocket landing = FakeIncusSocket.start()) {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceBackups backups = new InstanceBackups();
            int instanceId = instanceRecord("backup-with-nic", hostId);
            service.deploy(instanceId);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 0);
            Row nic = Models.get(InstanceDeviceModel.class).createEmptyRow();
            nic.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
            nic.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_NIC);
            nic.set(InstanceDeviceModel.NAME, "eth1");
            Models.get(InstanceDeviceModel.class).save(nic);
            FakeNativeDaemons.FakeWorkload source = FakeNativeDaemons.daemonOf(hostId)
                .get(FakeNativeDaemons.handleOf(instanceId));
            source.nics.add("eth1");
            Map<String, FakeNativeDaemons.FakeVolume> sourceVolumes = FakeNativeDaemons.volumesOf(hostId);
            Set<Integer> restored = new LinkedHashSet<>();
            int landingId = HostFixtures.admittedIncusHost("device-landing-host");
            Models.get(ServerModel.class).find().where(ServerModel.ID.eq(landingId))
                .assign(ServerModel.INCUS_URL, landing.url()).updateAll();
            try {
                // 1. The restored record has the interface's row under the name the daemon holds it by. The landing
                //    host never held one of this controller's extra interfaces, so the bridge they sit on does not
                //    exist there: the restore ensures it before the import, as attach and deploy do.
                assertThat(FakeNativeDaemons.EXTRA_BRIDGES).as("step 1: the landing host has no extra bridge yet")
                    .doesNotContain("device-landing-host");
                int withNic = backups.backupNow(instanceId, targetId, target);
                int copyId = backups.restoreToNew(withNic, "nic-copy", landingId).instanceId();
                restored.add(copyId);
                assertThat(FakeNativeDaemons.EXTRA_BRIDGES).as("step 1: the restore ensured the bridge first")
                    .contains("device-landing-host");
                assertThat(Models.get(InstanceDeviceModel.class).find()
                        .where(InstanceDeviceModel.INSTANCE_ID.eq(copyId)).all())
                    .as("step 1: the restored record has the interface's row").singleElement().satisfies(row -> {
                        assertThat((String) row.get(InstanceDeviceModel.NAME)).isEqualTo("eth1");
                        assertThat((String) row.get(InstanceDeviceModel.TYPE)).isEqualTo(InstanceDeviceModel.TYPE_NIC);
                    });
                assertThat(FakeNativeDaemons.daemonOf(landingId).get(FakeNativeDaemons.handleOf(copyId)).nics)
                    .as("step 1: and the restored machine holds that one interface").containsExactly("eth1");

                // 2. A data disk travels with the backup: the restored machine's disk is a volume of its own holding
                //    what the source's held, attributed to the new record, and the record has the disk's row at the
                //    captured size.
                FakeNativeDaemons.FakeVolume disk = new FakeNativeDaemons.FakeVolume();
                disk.data.put("marker", "disk-v1");
                disk.sizeGb = 3;
                sourceVolumes.put("source-data1", disk);
                source.disks.put("data1", "source-data1");
                int withDisk = backups.backupNow(instanceId, targetId, target);
                int diskCopyId = backups.restoreToNew(withDisk, "disk-copy", landingId).instanceId();
                restored.add(diskCopyId);
                FakeNativeDaemons.FakeWorkload diskCopy = FakeNativeDaemons.daemonOf(landingId)
                    .get(FakeNativeDaemons.handleOf(diskCopyId));
                String copyVolume = FakeNativeDaemons.handleOf(diskCopyId) + "-data1";
                assertThat(diskCopy.disks).as("step 2: the restored disk points at a volume of the copy's own naming")
                    .containsExactly(Map.entry("data1", copyVolume));
                assertThat(FakeNativeDaemons.volumesOf(landingId).get(copyVolume))
                    .as("step 2: that volume holds the source disk's contents, owned by the copy").satisfies(volume -> {
                        assertThat(volume.data).containsEntry("marker", "disk-v1");
                        assertThat(volume.ownerId).isEqualTo(diskCopy.ownerId);
                    });
                assertThat(Models.get(InstanceDeviceModel.class).find()
                        .where(InstanceDeviceModel.INSTANCE_ID.eq(diskCopyId))
                        .where(InstanceDeviceModel.TYPE.eq(InstanceDeviceModel.TYPE_DISK)).all())
                    .as("step 2: the copy has the disk's row at the captured size").singleElement().satisfies(row -> {
                        assertThat((String) row.get(InstanceDeviceModel.NAME)).isEqualTo("data1");
                        assertThat((Integer) row.get(InstanceDeviceModel.SIZE_GB)).isEqualTo(3);
                    });
                assertThat(RawValues.map((Object) Models.get(InstanceBackupModel.class).findById(withDisk)
                        .get(InstanceBackupModel.SUMMARY)).get("disks"))
                    .as("step 2: the backup row names the disk it carries").isEqualTo(List.of("data1"));

                // 3. Capacity counts the disk at its declared size, not its archive's: a landing pool of 2 GiB holds
                //    the export but not a 3 GB disk, and the restore is refused before any record exists.
                landing.daemon.poolTotalBytes = 2L * 1024 * 1024 * 1024;
                long beforeFull = Models.get(InstanceModel.class).find().count();
                Throwable full = catchThrowable(() -> restored.add(
                    backups.restoreToNew(withDisk, "disk-full", landingId).instanceId()));
                landing.daemon.poolTotalBytes = 100L * 1024 * 1024 * 1024;
                assertThat(full).as("step 3: the restore is refused").isInstanceOf(Violations.class);
                assertThat(((Violations) full).all()).as("step 3: for capacity").anySatisfy(violation ->
                    assertThat(violation.message().key()).isEqualTo("restore_capacity"));
                assertThat(Models.get(InstanceModel.class).find().count()).as("step 3: and nothing was created")
                    .isEqualTo(beforeFull);

                // 3b. The same refusal through the operation, then the same invocation once the pool has room: a
                //     refused restore leaves its invocation free, so pressing the dialog's submit again runs it.
                Row diskBackup = Models.get(InstanceBackupModel.class).findById(withDisk);
                UUID invocation = UUID.randomUUID();
                landing.daemon.poolTotalBytes = 2L * 1024 * 1024 * 1024;
                assertThat(violationKeys(catchThrowable(() -> OperationPipeline.invoke(restoreRequest(diskBackup)
                        .invocation(invocation).form(Map.of("serverId", landingId))))))
                    .as("step 3b: the operation refuses for capacity").isEqualTo("restore_capacity ");
                landing.daemon.poolTotalBytes = 100L * 1024 * 1024 * 1024;
                InstanceBackupOperations.Restored retried = (InstanceBackupOperations.Restored) OperationPipeline
                    .invoke(restoreRequest(diskBackup).invocation(invocation).form(Map.of("serverId", landingId)))
                    .value();
                restored.add(retried.instanceId());
                assertThat((Integer) Models.get(InstanceModel.class).findById(retried.instanceId())
                        .get(InstanceModel.SERVER_ID))
                    .as("step 3b: the same invocation submitted again ran the restore").isEqualTo(landingId);

                // 4. A backup an older build wrote names the disk but carries no volume: the restore is refused naming
                //    the disk, and it leaves no half-made record (one in error offered Restart) and no volume behind.
                FakeNativeDaemons.EXPORTS_DISKS.set(false);
                int legacy = backups.backupNow(instanceId, targetId, target);
                FakeNativeDaemons.EXPORTS_DISKS.set(true);
                long before = Models.get(InstanceModel.class).find().count();
                Set<String> volumesBefore = Set.copyOf(FakeNativeDaemons.volumesOf(landingId).keySet());
                Throwable refused = catchThrowable(() -> restored.add(
                    backups.restoreToNew(legacy, "legacy-copy", landingId).instanceId()));
                Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq("legacy-copy")).all()
                    .forEach(row -> restored.add(row.get(InstanceModel.ID)));
                assertThat(refused).as("step 4: the restore is refused").isInstanceOf(Violations.class);
                assertThat(((Violations) refused).all()).as("step 4: naming the data disk").anySatisfy(violation ->
                    assertThat(ApiSupport.shippedText(violation.message())).contains("data1"));
                assertThat(Models.get(InstanceModel.class).find().count())
                    .as("step 4: the failed restore leaves no record behind").isEqualTo(before);
                assertThat(FakeNativeDaemons.volumesOf(landingId).keySet())
                    .as("step 4: nor a volume it landed").isEqualTo(volumesBefore);
            } finally {
                FakeNativeDaemons.EXPORTS_DISKS.set(true);
                FakeNativeDaemons.EXTRA_BRIDGES.remove("device-landing-host");
                source.disks.clear();
                source.nics.clear();
                sourceVolumes.remove("source-data1");
                Models.get(InstanceDeviceModel.class).find().where(InstanceDeviceModel.INSTANCE_ID.eq(instanceId))
                    .all().forEach(row -> Models.get(InstanceDeviceModel.class).delete(row.get(InstanceDeviceModel.ID)));
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
                // Every record a restore created on the landing host, a held one included, so a failed step reports
                // its own assertion rather than the host's in-use refusal.
                Models.get(InstanceModel.class).find().where(InstanceModel.SERVER_ID.eq(landingId)).all()
                    .forEach(row -> restored.add(row.get(InstanceModel.ID)));
                restored.forEach(service::destroy);
                service.destroy(instanceId);
                Models.get(ServerModel.class).delete(landingId);
            }
        });
        }
    }

    private static Map<Integer, InstanceBackups.Destination> destinationsById(Row backup) {
        Map<Integer, InstanceBackups.Destination> byId = new HashMap<>();
        InstanceBackups.destinationsFor(backup).forEach(destination -> byId.put(destination.serverId(), destination));
        return byId;
    }

    private static OperationRequest<Row, ?, ?> restoreRequest(Row backup) {
        return OperationRequest.of(InstanceBackupOperations.RESTORE_BACKUP, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TenantConduits.operator()).subjects(List.of(backup));
    }

    private static void stampLastSeen(int serverId, Instant at) {
        Models.get(ServerModel.class).find().where(ServerModel.ID.eq(serverId))
            .assign(ServerModel.LAST_SEEN_AT, at).updateAll();
    }

    /** A backup whose instance row is gone (its id cleared) still deletes: the operator governs it alone. */
    @Test
    void aBackupWhoseInstanceIsGoneDeletesCleanly() {
        // 1. A failed backup row naming no instance (no artifact, so no target is asked).
        Row row = Models.get(InstanceBackupModel.class).createEmptyRow();
        row.set(InstanceBackupModel.INSTANCE_ID, (Integer) null);
        row.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED);
        Models.get(InstanceBackupModel.class).save(row);
        int id = row.get(InstanceBackupModel.ID);

        // 2. The delete runs as system work and removes the row instead of failing on the missing instance.
        new InstanceBackups().delete(id);
        assertThat(Models.get(InstanceBackupModel.class).findById(id))
            .as("step 2: the orphan backup row is gone").isNull();
    }

    /**
     * Restore is offered for, and accepted from, only a backup its status calls committed and that names the
     * committed key: a row still uploading, failed, without a status, or complete without a key never completed.
     */
    @Test
    void onlyACommittedBackupOffersItsRestore() {
        List<Integer> rows = new ArrayList<>();
        try {
            // 1. Every never-completed shape hides the Restore and refuses a direct restore by name.
            Object[][] neverCompleted = {
                {InstanceBackupModel.STATUS_UPLOADING, "backups/uploading.hib"},
                {InstanceBackupModel.STATUS_FAILED, "backups/failed.hib"},
                {null, null},
                {InstanceBackupModel.STATUS_COMPLETE, null},
            };
            for (Object[] shape : neverCompleted) {
                Row row = backupRow((String) shape[0], (String) shape[1]);
                rows.add(row.get(InstanceBackupModel.ID));
                String step = "status " + shape[0] + ", key " + shape[1] + ": ";
                assertThat(OperationPipeline.offer(InstanceBackupOperations.RESTORE_BACKUP, TenantConduits.operator(),
                        row))
                    .as("step 1 " + step + "the Restore is not offered")
                    .isInstanceOf(OperationPipeline.Offer.Hidden.class);
                assertThat(violationKeys(catchThrowable(() -> new InstanceBackups().restoreToNew(
                        row.get(InstanceBackupModel.ID), null, null))))
                    .as("step 1 " + step + "a direct restore is refused as not restorable")
                    .contains("backup_not_restorable");
            }

            // 2. A complete backup naming its committed key offers its Restore.
            Row committed = backupRow(InstanceBackupModel.STATUS_COMPLETE, "backups/committed.hib");
            rows.add(committed.get(InstanceBackupModel.ID));
            assertThat(OperationPipeline.offer(InstanceBackupOperations.RESTORE_BACKUP, TenantConduits.operator(),
                    committed))
                .as("step 2: a committed backup offers its Restore")
                .isNotInstanceOf(OperationPipeline.Offer.Hidden.class);
        } finally {
            rows.forEach(id -> Models.get(InstanceBackupModel.class).delete(id));
        }
    }

    /** @return a saved instance-less backup row of this status and remote key, both possibly null */
    private static Row backupRow(String status, String key) {
        Row row = Models.get(InstanceBackupModel.class).createEmptyRow();
        row.set(InstanceBackupModel.INSTANCE_ID, (Integer) null);
        row.set(InstanceBackupModel.REMOTE_KEY, key);
        Models.get(InstanceBackupModel.class).save(row);
        // The status is stamped after the save: a null one would otherwise take the field's default.
        Models.get(InstanceBackupModel.class).find().where(InstanceBackupModel.ID.eq(row.get(InstanceBackupModel.ID)))
            .assign(InstanceBackupModel.STATUS, status).updateAll();
        return Models.get(InstanceBackupModel.class).findById(row.get(InstanceBackupModel.ID));
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
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 1);
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

            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Backup.RETENTION, 7);
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
            Zenit.SETTINGS_VALUES.setValue(
                HohenheimSettings.Database.CONTROL_PLANE_BACKUP_TARGET,
                "delete-guard-target");
            try {
                assertThat(violationKeys(catchThrowable(() ->
                        Models.get(BackupTargetModel.class).delete((Object) guardTargetId))))
                    .as("step 4: the control-plane destination refuses with its own name")
                    .isEqualTo("backup_target_control_plane ");
            } finally {
                Zenit.SETTINGS_VALUES.setValue(
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
                .assign(InstanceModel.UPDATED_AT, Now.instant())
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

    private static int instanceRecord(String name, int serverId) {
        return BackupLaneFixture.instanceRecord(name, serverId);
    }
}
