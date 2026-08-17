package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.WorkloadIdentity;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.source.GitProvisioner;
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workload identity as an ENFORCED boundary: a uid is claimed by exactly one site,
 * a site without its own user is refused when enforcement applies (the setting, or
 * unconditionally for tenant-managed sites), and enforcement is the DEFAULT state.
 * Claims are recorded on system_users.site_id, never inferred from configuration.
 */
class WorkloadIdentityTest {

    private static Row siteA;
    private static Row siteB;

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        // This class boots a reduced path (no installAuthBaselines), so it declares the
        // grantable model itself; zenit-auth refuses a grant on an undeclared model.
        // BEFORE the boot stages, exactly as ServerMain does: zenit-auth's record-access
        // page registry is a MODULES-stage snapshot, and a declaration landing after the
        // drain is refused loudly by RecordAccessCoverage.
        HohenheimTestRuntime.declareAccessModelsOnce();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        ZenitAuth.init(HohenheimDatabase.datasource());

        saveUser("wl-user-a", 44301);
        saveUser("wl-user-b", 44302);
        siteA = saveSite("wl-site-a");
        siteB = saveSite("wl-site-b");
    }

    @AfterEach
    void lenientAgain() {
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Process.REQUIRE_DEDICATED_USER, false);
    }

    private static void saveUser(String name, int uid) {
        var model = Models.get(SystemUserModel.class);
        Row row = model.createEmptyRow();
        row.set(SystemUserModel.NAME, name);
        row.set(SystemUserModel.UID, uid);
        row.set(SystemUserModel.GID, uid);
        row.set(SystemUserModel.HOME, "/nonexistent");
        row.set(SystemUserModel.GECOS, "workload identity test");
        row.set(SystemUserModel.OBSOLETE, false);
        row.set(SystemUserModel.LAST_SEEN_AT, Instant.now());
        model.save(row);
    }

    private static Row saveSite(String name) {
        var model = Models.get(SiteModel.class);
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SITE_TYPE, "hohenheim:node");
        row.set(SiteModel.ENABLED, true);
        model.save(row);
        return row;
    }

    private static Integer claimOf(String userName) {
        Row row = Models.get(SystemUserModel.class).find()
            .where(SystemUserModel.NAME.eq(userName))
            .first();
        return row != null ? row.get(SystemUserModel.SITE_ID) : null;
    }

    /** A real script whose EXECUTION leaves a marker file, so "never spawned" is observable. */
    private static Path markerScript(Path marker) throws Exception {
        Path script = Files.createTempFile("wl-marker", ".js");
        Files.writeString(script,
            "require('fs').writeFileSync(" + quoted(marker) + ", 'ran');"
            + "setInterval(function(){}, 10000);");
        script.toFile().deleteOnExit();
        return script;
    }

    private static String quoted(Path path) {
        return "'" + path.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    @Test
    void identityIsClaimedExclusivelyAndRefusalsAreEnforced() throws Exception {
        int idA = siteA.get(SiteModel.ID);
        int idB = siteB.get(SiteModel.ID);

        // 1. Site A claims its user: the claim is RECORDED on the system_users row.
        assertThat(WorkloadIdentity.forSite(idA, "hohenheim:wl-user-a"))
            .as("1. site A resolves its dedicated user")
            .isNotNull()
            .extracting(user -> user.uid())
            .isEqualTo(44301);
        assertThat(claimOf("wl-user-a"))
            .as("1. the claim is recorded on the system_users row")
            .isEqualTo(idA);

        // 2. TWO SITES CANNOT CLAIM ONE UID: with enforcement on, site B configuring
        //    the same user gets a FaultedSiteHandler naming the conflict, and its
        //    script is NEVER EXECUTED (the marker file proves no process was spawned).
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Process.REQUIRE_DEDICATED_USER, true);
        Path markerB = Files.createTempFile("wl-marker-b", ".flag");
        Files.delete(markerB);
        Map<String, Object> settingsB = new HashMap<>();
        settingsB.put("script", markerScript(markerB).toString());
        settingsB.put("user", "hohenheim:wl-user-a");
        settingsB.put("minimum_processes", 1);
        SiteRequestHandler handlerB = new NodeSiteType().createHandler(siteB, settingsB);
        assertThat(handlerB)
            .as("2. the second claimant faults instead of spawning")
            .isInstanceOf(FaultedSiteHandler.class);
        assertThat(((FaultedSiteHandler) handlerB).reason())
            .as("2. the fault names the conflicting claim")
            .contains("wl-user-a")
            .contains("already claimed by site " + idA);
        Thread.sleep(500);
        assertThat(Files.exists(markerB))
            .as("2. NO process was spawned for the refused site (marker absent)")
            .isFalse();
        assertThat(claimOf("wl-user-a"))
            .as("2. the claim still belongs to site A")
            .isEqualTo(idA);

        // 3. A site with NO user is refused under require_dedicated_user.
        Path markerNoUser = Files.createTempFile("wl-marker-nouser", ".flag");
        Files.delete(markerNoUser);
        Map<String, Object> settingsNoUser = new HashMap<>();
        settingsNoUser.put("script", markerScript(markerNoUser).toString());
        settingsNoUser.put("minimum_processes", 1);
        SiteRequestHandler refused = new NodeSiteType().createHandler(siteB, settingsNoUser);
        assertThat(refused)
            .as("3. a userless site faults while enforcement is on")
            .isInstanceOf(FaultedSiteHandler.class);
        assertThat(((FaultedSiteHandler) refused).reason())
            .as("3. the fault says a dedicated user is required")
            .contains("no dedicated system user configured");
        assertThat(Files.exists(markerNoUser))
            .as("3. no process was spawned for the userless site")
            .isFalse();

        // 4. A DANGLING user reference faults explicitly instead of escaping as an
        //    uncaught IllegalStateException (the old catch only covered
        //    IllegalArgumentException, so the site silently vanished from routing).
        Map<String, Object> settingsDangling = new HashMap<>();
        settingsDangling.put("script", markerScript(markerNoUser).toString());
        settingsDangling.put("user", "hohenheim:wl-user-missing");
        SiteRequestHandler dangling = new NodeSiteType().createHandler(siteB, settingsDangling);
        assertThat(dangling)
            .as("4. a dangling user reference becomes an explicit fault, not an escape")
            .isInstanceOf(FaultedSiteHandler.class);
        assertThat(((FaultedSiteHandler) dangling).reason())
            .as("4. the fault names the missing user")
            .contains("wl-user-missing")
            .contains("does not exist");

        // 5. POSITIVE CONTROL with the setting OFF: the userless site runs, and its
        //    script really executes (the marker appears), proving step 2/3 refused
        //    because of policy, not because spawning is broken here.
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Process.REQUIRE_DEDICATED_USER, false);
        Path markerOk = Files.createTempFile("wl-marker-ok", ".flag");
        Files.delete(markerOk);
        Map<String, Object> settingsOk = new HashMap<>();
        settingsOk.put("script", markerScript(markerOk).toString());
        settingsOk.put("minimum_processes", 1);
        SiteRequestHandler lenient = new NodeSiteType().createHandler(siteB, settingsOk);
        try {
            assertThat(lenient)
                .as("5. the lenient mode still runs a userless site")
                .isNotInstanceOf(FaultedSiteHandler.class);
            for (int i = 0; i < 100 && !Files.exists(markerOk); i++) {
                Thread.sleep(100);
            }
            assertThat(Files.exists(markerOk))
                .as("5. the lenient site's process really ran (marker present)")
                .isTrue();
        } finally {
            lenient.destroy();
        }

        // 6. Switching site A to another user MOVES the claim: the new user is
        //    claimed and the old claim is released for someone else.
        assertThat(WorkloadIdentity.forSite(idA, "hohenheim:wl-user-b"))
            .as("6. site A switches to its new user")
            .isNotNull();
        assertThat(claimOf("wl-user-b")).as("6. the new user is claimed").isEqualTo(idA);
        assertThat(claimOf("wl-user-a")).as("6. the old claim was released").isNull();

        // 7. Site B can now adopt the released user.
        assertThat(WorkloadIdentity.forSite(idB, "hohenheim:wl-user-a"))
            .as("7. the released user is claimable by another site")
            .isNotNull();
        assertThat(claimOf("wl-user-a")).as("7. site B holds the claim").isEqualTo(idB);
    }

    @Test
    void tenantManagedSitesAreRefusedUnconditionally() {
        int idB = siteB.get(SiteModel.ID);

        // 1. Seed a tenant: a real user holding the "manage" capability on site B.
        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "tenant@workload.test");
        tenant.set(UserModel.DISPLAY_NAME, "Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        RecordGrants.grant(GrantSubjectType.USER, tenant.get(UserModel.ID), SiteModel.MODEL_ID, idB,
            "manage", true);

        // 2. Even with the transition setting OFF, the tenant-managed site is refused
        //    without a dedicated user: that invariant is unconditional.
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Process.REQUIRE_DEDICATED_USER, false);
        assertThatThrownBy(() -> WorkloadIdentity.forSite(idB, null))
            .as("2. a tenant-managed site never inherits the daemon identity")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no dedicated system user configured")
            .hasMessageContaining("tenant-managed");

        // 3. Cleanup so the sibling journey is not affected.
        RecordGrants.revoke(GrantSubjectType.USER, tenant.get(UserModel.ID), SiteModel.MODEL_ID, idB, "manage");
    }

    /**
     * Enforcement is the DEFAULT state, with nothing left that can quietly record it off
     * and nothing left that refuses to turn it back on.
     */
    @Test
    void enforcementIsOnByDefaultAndNothingRefusesTurningItBackOn() {
        int idA = siteA.get(SiteModel.ID);

        // 1. Clear every stored value: this is what a fresh install reads. The declared
        //    default is enforcement, and no boot path may persist anything weaker.
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Process.REQUIRE_DEDICATED_USER, null);
        assertThat(HohenheimSettings.VALUES.hasValue(
                HohenheimSettings.Process.REQUIRE_DEDICATED_USER))
            .as("1. nothing persisted a value for require_dedicated_user").isFalse();
        assertThat(HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Process.REQUIRE_DEDICATED_USER))
            .as("1. and it reads as enforced").isTrue();

        // 2. The default really ENFORCES: a site with no dedicated user is refused
        //    without anyone having set the flag. This is the gap that mattered -- the
        //    setting used to read as enforced while being recorded off underneath.
        assertThatThrownBy(() -> WorkloadIdentity.forSite(idA, null))
            .as("2. a userless site is refused on the default alone")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no dedicated system user configured");

        // 3. Turning enforcement OFF stays an explicit operator decision, and then the
        //    same site is tolerated -- so step 2 was policy, not a broken resolve.
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Process.REQUIRE_DEDICATED_USER, false);
        assertThat(WorkloadIdentity.forSite(idA, null))
            .as("3. the explicit opt-out tolerates inheriting the daemon user").isNull();

        // 4. Turning it back ON is ACCEPTED even while site A would fault. The gate that
        //    used to refuse this flip could only ever keep an install insecure; the
        //    refusal belongs on the individual site handler, not on the setting.
        assertThat(HohenheimSettings.Process.REQUIRE_DEDICATED_USER.coerce(Boolean.TRUE).accepted())
            .as("4. re-enabling enforcement is never refused").isTrue();
        assertThat(WorkloadIdentity.auditAll())
            .as("4. and the site that would fault is still REPORTED, on the attention list")
            .isNotEmpty();
    }

    /**
     * The git provisioning seam holds the same identity boundary as managed processes: it
     * CLAIMS the uid instead of resolving one, so a git site's clone/build cannot run under
     * a uid another site's workloads already own.
     */
    @Test
    void aGitSiteClaimsItsUidInsteadOfQuietlyBorrowingIt() throws Exception {
        int idA = siteA.get(SiteModel.ID);
        int idB = siteB.get(SiteModel.ID);
        saveUser("wl-git-incumbent", 44311);
        saveUser("wl-git-own", 44312);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Process.REQUIRE_DEDICATED_USER, true);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            Files.createTempDirectory("wl-git-data").toString());

        // 1. Site A owns a uid, recorded on its system_users row.
        assertThat(WorkloadIdentity.forSite(idA, "hohenheim:wl-git-incumbent"))
            .as("1. site A claims its uid").isNotNull();
        assertThat(claimOf("wl-git-incumbent")).as("1. the claim is recorded").isEqualTo(idA);

        // 2. A git site on ANOTHER site pointed at that same uid is refused BEFORE it
        //    clones or builds anything. A bare SystemUsers.resolve handed the uid over
        //    silently, which is the /proc-readable sharing the claim exists to stop.
        Map<String, Object> typeSettings = new HashMap<>();
        typeSettings.put("root_path", ".");
        typeSettings.put("user", "hohenheim:wl-git-incumbent");
        Map<String, Object> sourceSettings = new HashMap<>();
        sourceSettings.put("repository_url", "");
        sourceSettings.put("branch", "main");
        assertThatThrownBy(() -> GitProvisioner.createHandler(siteB,
                SiteTypes.getHandler("hohenheim:static"), typeSettings, sourceSettings, idB))
            .as("2. a git site cannot borrow another site's uid")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("wl-git-incumbent")
            .hasMessageContaining("already claimed by site " + idA);
        assertThat(claimOf("wl-git-incumbent"))
            .as("2. and the incumbent's claim is untouched").isEqualTo(idA);

        // 3. With its OWN uid the handler is built AND the claim lands. This is the half a
        //    resolve-only seam could never do, so it is what proves the routing changed.
        typeSettings.put("user", "hohenheim:wl-git-own");
        SiteRequestHandler handler = GitProvisioner.createHandler(siteB,
            SiteTypes.getHandler("hohenheim:static"), typeSettings, sourceSettings, idB);
        try {
            assertThat(claimOf("wl-git-own"))
                .as("3. the git site RECORDED its claim, it did not merely resolve a uid")
                .isEqualTo(idB);
        } finally {
            // Teardown blocks on the cancelled deploy's child process (up to the queue's
            // own 30s join), which this test has no stake in: hand it to a daemon thread.
            Thread teardown = new Thread(handler::destroy, "wl-git-teardown");
            teardown.setDaemon(true);
            teardown.start();
            teardown.join(2_000);
        }
    }
}
