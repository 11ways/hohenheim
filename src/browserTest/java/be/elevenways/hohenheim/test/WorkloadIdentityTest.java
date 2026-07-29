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
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
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
 * unconditionally for tenant-managed sites), and the settings gate refuses enabling
 * enforcement while a site would fault. Claims are recorded on system_users.site_id,
 * never inferred from configuration.
 */
class WorkloadIdentityTest {

    private static Row siteA;
    private static Row siteB;

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
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
        HohenheimSettings.Process.DEDICATED_USER_GATE = null;
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
        RecordGrants.grant("user", tenant.get(UserModel.ID), SiteModel.MODEL_ID, idB,
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
        RecordGrants.revoke("user", tenant.get(UserModel.ID), SiteModel.MODEL_ID, idB, "manage");
    }

    @Test
    void theSettingsGateRefusesEnablingWhileASiteWouldFault() {
        // 1. Site B is enabled with NO user configured (its stored settings are empty),
        //    so the audit reports it and the gate must refuse the flip.
        WorkloadIdentity.installSettingsGate();
        assertThatThrownBy(() ->
                HohenheimSettings.Process.REQUIRE_DEDICATED_USER.coerce(Boolean.TRUE))
            .as("1. enabling enforcement is refused while a site would fault")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("require_dedicated_user")
            .hasMessageContaining("wl-site-a")
            .hasMessageContaining("no dedicated system user configured");

        // 2. Turning it OFF is never gated (the escape hatch stays open).
        assertThat(HohenheimSettings.Process.REQUIRE_DEDICATED_USER.coerce(Boolean.FALSE)
                .accepted())
            .as("2. disabling enforcement always coerces")
            .isTrue();
    }
}
