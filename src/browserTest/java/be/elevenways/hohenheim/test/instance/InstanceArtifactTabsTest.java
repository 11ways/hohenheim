package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.cms.InstanceBackupParts;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        backup.set(InstanceBackupModel.REMOTE_KEY, "artifact-tabs/complete.hib");
        backup.set(InstanceBackupModel.SIZE_BYTES, 67_890L);
        backup.set(InstanceBackupModel.CREATED_AT, Instant.parse("2026-08-10T13:00:00Z"));
        backup.set(InstanceBackupModel.SUMMARY, Map.of("kind", "hohenheim:docker_container",
            "disks", List.of("artifactdisk-a", "artifactdisk-b")));
        backups.save(backup);
        backupId = backup.get(InstanceBackupModel.ID);

        // A delegate holding ONLY console: enough to see the record, not enough for
        // either artifact tab.
        consoleUserId = ApiSupport.user("artifact-console@hohenheim.local", "Console Only");
        RecordGrants.grant(GrantSubjectType.USER, consoleUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.CONSOLE, true);

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

        // 4. The data disks a backup carries are named in its row and on its own page, read off its stored summary.
        assertThat(backups.body()).as("step 4: the backup row names the data disks it carries")
            .contains("artifactdisk-a, artifactdisk-b");
        String page = httpGet(backupPage(HohenheimSlugs.ADMIN, backupId), sessionToken).body();
        assertThat(page).as("step 4: and so does the backup's page").contains("artifactdisk-a, artifactdisk-b");

        // 5. A backup is titled by when it was taken, also when its archive has a key: the key is a field on the page,
        //    never its heading or its breadcrumb.
        Matcher title = Pattern.compile("<title>([^<]*)</title>").matcher(page);
        assertThat(title.find()).as("step 5: the backup's page has a title").isTrue();
        assertThat(title.group(1)).as("step 5: the title is when the backup was taken")
            .contains("2026-08-10 13:00 UTC").doesNotContain("complete.hib");
        assertThat(page).as("step 5: the key stays a field on the page").contains("artifact-tabs/complete.hib");
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

    /**
     * A backup outlives its instance: once the instance is deleted, the backup is the way to bring it back on another
     * host, so its record still offers restore-to-new (with the host pick) and delete, under the same gates.
     */
    @Test
    void aBackupWhoseInstanceIsGoneStillOffersRestoreAndDelete() throws Exception {
        // 1. A complete backup of an instance that is then deleted.
        var instances = Models.get(InstanceModel.class);
        Row doomed = instances.createEmptyRow();
        doomed.set(InstanceModel.NAME, "artifact-gone");
        doomed.set(InstanceModel.KIND, "hohenheim:docker_container");
        doomed.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        doomed.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        doomed.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(doomed);
        int goneId = doomed.get(InstanceModel.ID);
        // A complete backup names its committed key on its target; the delete in step 4 removes the (absent) artifact.
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "artifact-orphan-target");
        target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        target.set(BackupTargetModel.SETTINGS, Map.of("path", System.getProperty("java.io.tmpdir")));
        Models.get(BackupTargetModel.class).save(target);
        Row orphan = Models.get(InstanceBackupModel.class).createEmptyRow();
        orphan.set(InstanceBackupModel.INSTANCE_ID, goneId);
        orphan.set(InstanceBackupModel.TARGET_ID, target.get(BackupTargetModel.ID));
        orphan.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_COMPLETE);
        orphan.set(InstanceBackupModel.REMOTE_KEY, "artifact-tabs-orphan.hib");
        orphan.set(InstanceBackupModel.SUMMARY, Map.of("kind", "hohenheim:docker_container"));
        Models.get(InstanceBackupModel.class).save(orphan);
        int orphanId = orphan.get(InstanceBackupModel.ID);
        assertThat(instances.delete(goneId)).as("step 1: the instance is deleted").isTrue();
        assertThat(instances.findById(goneId)).as("step 1: and gone from every default read").isNull();

        // 2. The backup's record page still offers restore-to-new and delete.
        HttpResponse<String> page = httpGet(backupPage(HohenheimSlugs.ADMIN, orphanId), sessionToken);
        assertThat(page.statusCode()).as("step 2: the backup record renders").isEqualTo(200);
        assertThat(page.body())
            .withFailMessage("step 2: the backup of a deleted instance offers no restore")
            .containsPattern("/admin/instance-backups/invoke/hohenheim\\.restore_backup\\?ids=" + orphanId
                + "(?![0-9])");
        assertThat(page.body())
            .withFailMessage("step 2: the backup of a deleted instance offers no delete")
            .containsPattern("(/admin/instance-backups/" + orphanId + "/delete|delete_backup\\?ids=" + orphanId
                + "(?![0-9]))");

        // 2b. The backup names its instance by title, marked deleted, on its record page and in the backup list:
        //     a relation to a trashed record the operator may read is never a bare id.
        assertThat(page.body()).as("step 2b: the record page names the deleted instance")
            .contains("artifact-gone (deleted)");
        assertThat(httpGet("/admin/" + HohenheimSlugs.INSTANCE_BACKUPS, sessionToken).body())
            .as("step 2b: and so does the backup list").contains("artifact-gone (deleted)");

        // 3. The restore's host pick lists the hosts a restore could land on, read off the backup alone.
        assertThat(InstanceBackups.destinationsFor(Models.get(InstanceBackupModel.class).findById(orphanId)))
            .as("step 3: the host pick offers this installation's host")
            .anySatisfy(destination -> assertThat(destination.serverId()).isEqualTo(ServerModel.localServerId()));

        // 4. The operator's delete removes it through the entry's own DELETE.
        try {
            PanelResourceCalls.delete(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_BACKUPS, orphanId,
                TenantConduits.operator());
            assertThat(Models.get(InstanceBackupModel.class).findById(orphanId)).as("step 4: the backup is deleted")
                .isNull();
        } finally {
            Models.get(BackupTargetModel.class).delete(target.get(BackupTargetModel.ID));
        }
    }

    /**
     * A deleted referent is named only to whom may read it: a tenant holding backups but not view on an instance never
     * reads that instance's name off its backup, live or trashed (once trashed the backup's page refuses the tenant,
     * whose way to it was the instance), while the operator reads the name marked deleted.
     */
    @Test
    void aTenantWhoCannotReadTheTrashedInstanceSeesItsBackupWithoutItsName() throws Exception {
        // 1. A backup of an instance the tenant holds BACKUPS (and nothing else) on.
        var instances = Models.get(InstanceModel.class);
        Row hidden = instances.createEmptyRow();
        hidden.set(InstanceModel.NAME, "unreadable-gone");
        hidden.set(InstanceModel.KIND, "hohenheim:docker_container");
        hidden.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        hidden.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        hidden.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(hidden);
        int hiddenId = hidden.get(InstanceModel.ID);
        Row backup = Models.get(InstanceBackupModel.class).createEmptyRow();
        backup.set(InstanceBackupModel.INSTANCE_ID, hiddenId);
        backup.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_COMPLETE);
        backup.set(InstanceBackupModel.CREATED_AT, Instant.parse("2026-08-11T09:30:00Z"));
        Models.get(InstanceBackupModel.class).save(backup);
        int backupRowId = backup.get(InstanceBackupModel.ID);
        Integer tenantId = ApiSupport.user("artifact-backups@hohenheim.local", "Backups Only");
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, hiddenId,
            HohenheimCapabilities.BACKUPS, true);
        String tenantSession = sessionFor(tenantId).token();
        String pagePath = backupPage(HohenheimSlugs.MANAGE, backupRowId);

        // 2. Live: the tenant reaches the backup, headed by when it was taken (no keyless "InstanceBackup #N").
        HttpResponse<String> live = httpGet(pagePath, tenantSession);
        assertThat(live.statusCode()).as("step 2: the tenant reaches its backup").isEqualTo(200);
        assertThat(live.body()).as("step 2: headed by when it was taken").contains("2026-08-11 09:30 UTC")
            .doesNotContain("InstanceBackup #");

        // 3. Trashed: the record page refuses the tenant, and the backup list never names the instance.
        assertThat(instances.delete(hiddenId)).as("step 3: the instance is trashed").isTrue();
        HttpResponse<String> trashed = httpGet(pagePath, tenantSession);
        assertThat(trashed.statusCode()).as("step 3: the backup page refuses the tenant").isEqualTo(403);
        assertThat(trashed.body()).as("step 3: and names nothing").doesNotContain("unreadable-gone");
        assertThat(httpGet("/manage/" + HohenheimSlugs.INSTANCE_BACKUPS, tenantSession).body())
            .as("step 3: nor does its backup list").doesNotContain("unreadable-gone");

        // 4. The positive anchor: the operator, who may read the trashed instance, reads its name marked deleted.
        assertThat(httpGet(backupPage(HohenheimSlugs.ADMIN, backupRowId), sessionToken).body())
            .as("step 4: the operator reads the deleted instance by name")
            .contains("unreadable-gone (deleted)");
    }

    /**
     * Where an instance's backups go is its own choice, made in its Configuration: Back up now without one says so in
     * words, and the Backups tab names the target in use with the way to change it.
     */
    @Test
    void backingUpWithoutATargetSaysWhereToChooseOneAndTheTabNamesTheTargetInUse() throws Exception {
        // 1. Without a target, Back up now is refused naming where the target is chosen.
        Throwable refused = catchThrowable(() -> new InstanceBackups().backupNow(instanceId));
        assertThat(refused).as("step 1: refused").isInstanceOf(Violations.class);
        assertThat(((Violations) refused).all()).as("step 1: by the instance's own missing choice")
            .anySatisfy(violation -> {
                assertThat(violation.message().key()).isEqualTo("instance_backup_target_none");
                assertThat(ApiSupport.shippedText(violation.message())).contains("artifact-subject")
                    .contains("Configuration").contains("Backups");
            });

        // 2. The Backups tab says none is chosen and links to the Configuration, where the field is not folded away.
        HttpResponse<String> none = httpGet(url("backups"), sessionToken);
        assertThat(none.body()).as("step 2: the tab offers to choose one").contains("Choose a backup target")
            .contains("href=\"/admin/instances/" + instanceId + "\"");
        assertThat(httpGet("/admin/instances/" + instanceId, sessionToken).body())
            .as("step 2: the Configuration shows its Backups section open")
            .contains("data-section=\"backups\" data-collapsed=\"false\"");

        // 3. With a target chosen, the tab names it.
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "artifact-tab-destination");
        target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        target.set(BackupTargetModel.SETTINGS, Map.of("path", System.getProperty("java.io.tmpdir")));
        Models.get(BackupTargetModel.class).save(target);
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.BACKUP_TARGET_ID, (Integer) target.get(BackupTargetModel.ID)).updateAll();
        try {
            assertThat(httpGet(url("backups"), sessionToken).body()).as("step 3: the tab names the target in use")
                .contains("Backups go to artifact-tab-destination");
        } finally {
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.BACKUP_TARGET_ID, (Integer) null).updateAll();
            Models.get(BackupTargetModel.class).delete(target.get(BackupTargetModel.ID));
        }
    }

    /**
     * An application built from git (walk-site on starfleet) is offered Back up now disabled, naming why, and asking
     * anyway is refused by the same rule: its backup lane could never reproduce the release it would restore.
     */
    @Test
    void aGitBuiltApplicationsBackUpNowIsRefusedByTheOfferRuleWithItsReason() {
        Row target = Models.get(BackupTargetModel.class).createEmptyRow();
        target.set(BackupTargetModel.NAME, "git-app-destination");
        target.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        target.set(BackupTargetModel.SETTINGS, Map.of("path", System.getProperty("java.io.tmpdir")));
        Models.get(BackupTargetModel.class).save(target);
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        app.set(InstanceModel.NAME, "git-built-app");
        app.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        app.set(InstanceModel.SETTINGS, Map.of("repository_url", "https://example.invalid/app.git", "branch", "main"));
        app.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        app.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        app.set(InstanceModel.BACKUP_TARGET_ID, (Integer) target.get(BackupTargetModel.ID));
        Models.get(InstanceModel.class).save(app);
        int appId = app.get(InstanceModel.ID);

        // 1. THE rule behind the offer: with a target chosen, the git source is the reason it cannot run.
        assertThat(InstanceBackups.backupRefusal(Models.get(InstanceModel.class).findById(appId)))
            .as("step 1: the offer is disabled by the source, not by a missing target").isNotNull()
            .satisfies(reason -> assertThat(reason.key()).isEqualTo("instance_backup_source_unreproducible"));

        // 2. Asking anyway is refused by the same rule, in words that name the application.
        Throwable refused = catchThrowable(() -> new InstanceBackups().backupNow(appId));
        assertThat(refused).as("step 2: refused").isInstanceOf(Violations.class);
        assertThat(((Violations) refused).all()).as("step 2: by the same reason")
            .anySatisfy(violation -> {
                assertThat(violation.message().key()).isEqualTo("instance_backup_source_unreproducible");
                assertThat(ApiSupport.shippedText(violation.message())).contains("git-built-app");
            });

        // 3. A plain instance with the same target is offered: the rule is the application's source alone.
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
            .assign(InstanceModel.BACKUP_TARGET_ID, (Integer) target.get(BackupTargetModel.ID)).updateAll();
        try {
            assertThat(InstanceBackups.backupRefusal(Models.get(InstanceModel.class).findById(instanceId)))
                .as("step 3: a docker container with a target may be backed up").isNull();
        } finally {
            Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(instanceId))
                .assign(InstanceModel.BACKUP_TARGET_ID, (Integer) null).updateAll();
        }
    }

    // -- plumbing -----------------------------------------------------------------

    private static String url(String slug) {
        return "/admin/instances/" + instanceId + "/page/" + slug;
    }

    /** A backup's record page: its overview, where the bare record route lands. */
    private static String backupPage(String panel, int backupId) {
        return CmsRoutes.subpage(panel, HohenheimSlugs.INSTANCE_BACKUPS, backupId, RecordOverview.SLUG).toUrl();
    }
}
