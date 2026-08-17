package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.ManageInstanceBackupResource;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Per-instance Snapshots and Backups tabs: a SCOPED VIEW of the panel-wide resources,
 * never a second UI over the same rows.
 *
 * AIDEV-NOTE: the pre-fix defect, verified 2026-08-11 -- both slugs answered 404, so a
 * snapshot or backup could only be found by opening a flat installation-wide list and
 * filtering it by instance id. The gating half is the counterfactual: a tenant holding
 * ONLY console on the record must reach neither tab, and restore-to-new must stay off
 * the delegated surface entirely.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, "artifact-console@hohenheim.local");
        user.set(UserModel.DISPLAY_NAME, "Console Only");
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        consoleUserId = user.get(UserModel.ID);
        RecordGrants.grant(GrantSubjectType.USER, consoleUserId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.CONSOLE, true);

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, (long) consoleUserId);
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);
        consoleSession = session.token().secret();
    }

    /**
     * Both tabs exist, show THIS record's rows, and relay the owning resource's own
     * actions instead of hand-rolling a second restore button.
     */
    @Test
    @Order(1)
    void bothTabsScopeTheResourcesRowsToThisRecordAndRelayItsActions() throws Exception {
        // 1. THE ABSENCE: pre-fix both slugs answered 404.
        HttpResponse<String> snapshots = get(url("snapshots"), sessionToken);
        assertThat(snapshots.statusCode())
            .withFailMessage("step 1: the per-instance snapshots tab does not exist (HTTP %s)",
                snapshots.statusCode())
            .isEqualTo(200);
        HttpResponse<String> backups = get(url("backups"), sessionToken);
        assertThat(backups.statusCode())
            .withFailMessage("step 1: the per-instance backups tab does not exist (HTTP %s)",
                backups.statusCode())
            .isEqualTo(200);

        // 2. Each shows its own record's row, with the stored detail.
        assertThat(snapshots.body()).as("step 2: the snapshot row renders")
            .contains("data-artifact-id=\"" + snapshotId + "\"")
            .contains("before the risky upgrade");
        assertThat(backups.body()).as("step 2: the backup row renders")
            .contains("data-artifact-id=\"" + backupId + "\"")
            .contains("67890");

        // 3. Restore is the RESOURCE's declared action, targeting the resource's own
        //    invoke route -- not a form this page invented.
        assertThat(snapshots.body())
            .withFailMessage("step 3: the snapshot restore action is not relayed from"
                + " InstanceSnapshotResource")
            .contains("/admin/instance-snapshots/" + snapshotId + "/action/restore_snapshot");
        assertThat(backups.body())
            .withFailMessage("step 3: the backup restore action is not relayed from"
                + " InstanceBackupResource")
            .contains("/admin/instance-backups/" + backupId + "/action/restore_backup");

        // 4. And the row links back into the generated record page, which stays the one
        //    place a row is deleted -- no second delete UI.
        assertThat(snapshots.body()).as("step 4: the row links into the generated resource")
            .contains("/admin/instance-snapshots/" + snapshotId);
    }

    /**
     * Each tab answers to the capability its OPERATIONS answer to, and the gate is on the
     * route as well as on the tab strip.
     */
    @Test
    @Order(2)
    void aConsoleOnlyDelegateReachesNeitherTab() throws Exception {
        // 1. The delegate genuinely reaches the record -- without this the rest is vacuous.
        HttpResponse<String> record = get("/manage/instances/" + instanceId, consoleSession);
        assertThat(record.statusCode()).as("step 1: the console delegate sees the record")
            .isEqualTo(200);

        // 2. Neither artifact tab is offered.
        assertThat(record.body())
            .withFailMessage("step 2: a console-only delegate is offered the snapshots tab")
            .doesNotContain("/page/snapshots");
        assertThat(record.body())
            .withFailMessage("step 2: a console-only delegate is offered the backups tab")
            .doesNotContain("/page/backups");

        // 3. Nor reachable by hand: an unoffered slug 404s.
        assertThat(get("/manage/instances/" + instanceId + "/page/snapshots", consoleSession)
                .statusCode())
            .withFailMessage("step 3: the snapshots route is open to a console-only delegate")
            .isEqualTo(404);
        assertThat(get("/manage/instances/" + instanceId + "/page/backups", consoleSession)
                .statusCode())
            .withFailMessage("step 3: the backups route is open to a console-only delegate")
            .isEqualTo(404);

        // 4. Positive anchor: the console tab the delegate DOES hold is offered, so
        //    step 2's absences are the capability gate and not an empty tab strip.
        assertThat(record.body()).as("step 4: the console tab is offered")
            .contains("/page/console");
    }

    /**
     * Restore-to-new stays OPERATOR-ONLY; building a per-instance view did not quietly
     * widen it. The AUTHORITY is what this proves -- a surface probe alone would pass on
     * a restore-to-new added as a subpage or a bulk action.
     */
    @Test
    @Order(3)
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
        assertThat(new ManageInstanceBackupResource().rowActions())
            .withFailMessage("step 3: the delegated backup resource declares a row action;"
                + " restore-to-new is refused underneath it, so a rendered button here"
                + " could only fail")
            .isEmpty();
    }

    // -- plumbing -----------------------------------------------------------------

    private static String url(String slug) {
        return "/admin/instances/" + instanceId + "/page/" + slug;
    }

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
