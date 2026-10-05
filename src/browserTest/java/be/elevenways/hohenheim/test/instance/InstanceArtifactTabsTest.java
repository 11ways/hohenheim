package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.InstanceBackupParts;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The per-instance Backups tab: a SCOPED VIEW of the panel-wide backup and snapshot resources (the framework's child
 * list), never a second UI over the same rows.
 *
 * AIDEV-NOTE: the pre-fix defect, verified 2026-08-11 -- both slugs answered 404, so a
 * snapshot or backup could only be found by opening a flat installation-wide list and
 * filtering it by instance id. The gating half is the counterfactual: a tenant holding
 * ONLY console on the record must see no artifact row, and restore-to-new must stay off
 * the delegated surface entirely.
 */
class InstanceArtifactTabsTest extends HohenheimTestBase {

    private static Integer instanceId;
    private static Integer snapshotId;
    private static Integer backupId;
    private static String consoleSession;
    private static Integer consoleUserId;

    @BeforeAll
    static void seed() {
        var instances = Models.get(InstanceModel.class);
        Row instance = instances.createEmptyRow();
        instance.set(InstanceModel.NAME, "artifact-subject");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(instance);
        instanceId = instance.get(InstanceModel.ID);

        var snapshots = Models.get(InstanceSnapshotModel.class);
        Row snapshot = snapshots.createEmptyRow();
        snapshot.set(InstanceSnapshotModel.INSTANCE_ID, instanceId);
        snapshot.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_COMPLETE);
        snapshot.set(InstanceSnapshotModel.NOTE, "before the risky upgrade");
        snapshot.set(InstanceSnapshotModel.TOTAL_BYTES, 12_345L);
        snapshot.set(InstanceSnapshotModel.CREATED_AT, Instant.parse("2026-08-10T12:00:00Z"));
        snapshots.save(snapshot);
        snapshotId = snapshot.get(InstanceSnapshotModel.ID);

        var backups = Models.get(InstanceBackupModel.class);
        Row backup = backups.createEmptyRow();
        backup.set(InstanceBackupModel.INSTANCE_ID, instanceId);
        backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_COMPLETE);
        backup.set(InstanceBackupModel.SIZE_BYTES, 67_890L);
        backup.set(InstanceBackupModel.CREATED_AT, Instant.parse("2026-08-10T13:00:00Z"));
        backups.save(backup);
        backupId = backup.get(InstanceBackupModel.ID);

        // A delegate holding ONLY console: enough to see the record, not enough for
        // either artifact tab.
        consoleUserId = ApiSupport.user("artifact-console@hohenheim.local", "Console Only");
        RecordGrants.grant(GrantSubjectType.USER, consoleUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONSOLE, true);

        consoleSession = sessionFor(consoleUserId).token();
    }

    /**
     * The Backups tab lists THIS record's backups and snapshots as sections of the panel's own resources, and relays
     * their actions instead of hand-rolling a second restore button.
     */
    @Test
    void theBackupsTabScopesTheResourcesRowsToThisRecordAndRelaysTheirActions() throws Exception {
        // 1. One tab holds both artifact kinds; the separate snapshots route is retired.
        HttpResponse<String> backups = httpGet(url("backups"), sessionToken);
        assertThat(backups.statusCode())
            .withFailMessage("step 1: the per-instance Backups tab does not exist (HTTP %s)", backups.statusCode())
            .isEqualTo(200);
        assertThat(httpGet(url("snapshots"), sessionToken).statusCode())
            .as("step 1: snapshots are a section of the Backups tab, not a route of their own")
            .isEqualTo(404);

        // 2. Each section shows its own record's row, with the stored detail, linking into the generated record page,
        //    which stays the one place a row is deleted.
        assertThat(backups.body()).as("step 2: the snapshot row renders with its note and record link")
            .contains("before the risky upgrade")
            .contains("/admin/instance-snapshots/" + snapshotId);
        assertThat(backups.body()).as("step 2: the backup row renders with its record link")
            .contains("/admin/instance-backups/" + backupId);

        // 3. Restore is the RESOURCE's declared action, targeting the resource's own invoke route -- not a form this
        //    page invented.
        assertThat(backups.body())
            .withFailMessage("step 3: the snapshot restore operation is not relayed from the panel's snapshot entry")
            .contains("/admin/instance-snapshots/invoke/hohenheim.restore_snapshot?ids=" + snapshotId);
        assertThat(backups.body())
            .withFailMessage("step 3: the backup restore operation is not relayed from the panel's backup entry")
            .contains("/admin/instance-backups/invoke/hohenheim.restore_backup?ids=" + backupId);
    }

    /**
     * Each section answers to the capability its rows answer to: a delegate holding only console reaches the tab
     * (the record's schedules live there too) and sees none of the artifacts it may not touch.
     */
    @Test
    void aConsoleOnlyDelegateSeesNoArtifactRow() throws Exception {
        // 1. The delegate genuinely reaches the record -- without this the rest is vacuous.
        HttpResponse<String> record = httpGet("/manage/instances/" + instanceId, consoleSession);
        assertThat(record.statusCode()).as("step 1: the console delegate sees the record")
            .isEqualTo(200);

        // 2. The Backups tab renders, and lists neither artifact: each section is the delegated twin's own scope.
        HttpResponse<String> backups = httpGet("/manage/instances/" + instanceId + "/page/backups", consoleSession);
        assertThat(backups.statusCode()).as("step 2: the Backups tab renders for the delegate")
            .isEqualTo(200);
        assertThat(backups.body())
            .withFailMessage("step 2: a console-only delegate is shown the snapshot")
            .doesNotContain("before the risky upgrade")
            .doesNotContain("/manage/instance-snapshots/" + snapshotId);
        assertThat(backups.body())
            .withFailMessage("step 2: a console-only delegate is shown the backup")
            .doesNotContain("/manage/instance-backups/" + backupId);

        // 3. Positive anchor: the console tab the delegate DOES hold is offered, so step 2's absences are the
        //    capability scope and not an empty record.
        assertThat(record.body()).as("step 3: the console tab is offered")
            .contains("/page/console");
    }

    /**
     * Restore-to-new stays OPERATOR-ONLY; building a per-instance view did not quietly
     * widen it. The AUTHORITY is what this proves -- a surface probe alone would pass on
     * a restore-to-new added as a subpage or a bulk action.
     */
    @Test
    void restoreToNewRefusesTheTenantAndStaysOffTheDelegatedSurface() {
        // 1. A resolvable target, so the tenant call reaches the authority instead of
        //    dying on an unresolvable one -- the refusal must be the OPERATOR gate.
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "artifact-tab-target");
        target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        target.set(BackupTargetModel.SETTINGS,
            Map.of("path", System.getProperty("java.io.tmpdir")));
        Models.get(BackupTargetModel.class).save(target);
        Row backup = Models.get(InstanceBackupModel.class).findById(backupId);
        backup.set(InstanceBackupModel.TARGET_ID, target.get(BackupTargetModel.ID));
        Models.get(InstanceBackupModel.class).save(backup);
        try {
            // 2. THE CLAIM: the delegate drives restore-to-new directly, bypassing every
            //    surface -- the authority refuses it BY NAME. Restore-to-new creates an
            //    instance outside the creation funnel: no create authority, no placement
            //    decision, no creator grant.
            long instancesBefore = Models.get(InstanceModel.class).find().count();
            Throwable[] thrown = new Throwable[1];
            TenantConduits.as(new UserPrincipal(consoleUserId, "Console Only"),
                () -> thrown[0] = catchThrowable(() ->
                    new InstanceBackups().restoreToNew(backupId, "not-yours", null)));
            assertThat(thrown[0])
                .withFailMessage("step 2: a tenant-originated restore-to-new was not refused"
                    + " by the authority -- the missing row action would be the ONLY thing"
                    + " standing between a delegate and an off-funnel instance")
                .isInstanceOf(Violations.class);
            assertThat(((Violations) thrown[0]).all())
                .as("step 2: and the refusal is the operator-only one, not an incidental"
                    + " failure that happens to look like a gate")
                .anyMatch(violation ->
                    violation.message().key().equals("backup_restore_operator_only"));
            assertThat(Models.get(InstanceModel.class).find().count())
                .as("step 2: STATE -- the refused restore created nothing")
                .isEqualTo(instancesBefore);

            // 3. Secondary anchor: the delegated resource offers no row action either, so the
            //    tenant is never shown a button that could only fail.
            assertThat(InstanceBackupParts.manage().actions())
                .withFailMessage("step 3: the delegated backup resource declares a row action;"
                    + " restore-to-new is refused underneath it, so a rendered button here"
                    + " could only fail")
                .isEmpty();
        } finally {
            // Hand the shared backup row back untargeted, so the tab journeys never meet
            // a target this one introduced, whatever order they run in.
            Row untargeted = Models.get(InstanceBackupModel.class).findById(backupId);
            untargeted.set(InstanceBackupModel.TARGET_ID, (Integer) null);
            Models.get(InstanceBackupModel.class).save(untargeted);
            Models.get(BackupTargetModel.class).delete(target.get(BackupTargetModel.ID));
        }
    }

    // -- plumbing -----------------------------------------------------------------

    private static String url(String slug) {
        return "/admin/instances/" + instanceId + "/page/" + slug;
    }
}
