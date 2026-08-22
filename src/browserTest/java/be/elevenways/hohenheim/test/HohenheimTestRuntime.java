package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.setting.ServerSettings;

import java.io.File;
import java.io.IOException;

/**
 * Boots the server-side runtime with a temporary default datasource when the test has not provided one.
 */
public final class HohenheimTestRuntime {

    private static boolean accessModelsDeclared;

    private HohenheimTestRuntime() {
    }

    // AIDEV-NOTE: this boots the REAL TaskService -- the zenit TASKS stage is an
    // unconditional ROOT_STAGE child, and ensureBooted enables all 7 roles, so
    // minutely and */5 crons (SuperviseProxyListeners, MonitorStacks,
    // Verify*Isolation) tick throughout every browser test. Anything published via
    // ServerMain.adoptProxyServer becomes visible to those ticks MID-ASSERTION:
    // superviseListeners() mutates the very restart counters and listener state a
    // proxy test is checking. Detach with adoptProxyServer(null) when done.
    public static void ensureBooted() {
        // Standalone tests that never load the hohenheim settings file still hit
        // role gates (task declarations at the TASKS stage, panel filtering, the
        // security engine). The funnel DECLARES the full-node role set for them,
        // matching what these suites always exercised; a role-restricted test
        // declares its own set (and captures) BEFORE calling ensureBooted.
        if (!HohenheimRoles.isCaptured()) {
            HohenheimSettingsFiles.forceDefinitions();
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.PROXY, true);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.DNS, true);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.FIREWALL, true);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.STACKS, true);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.DATABASES, true);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Roles.INSTANCES, true);
            HohenheimRoles.capture();
        }
        declareAccessModelsOnce();
        ensureDatasource();
        // AIDEV-NOTE: auth is installed BEFORE the boot stages, exactly where
        // ServerMain installs it, because the MODULES stage now builds both CMS
        // panels and their resources are zenit-auth's. init() is idempotent, so a
        // test that already installed auth itself is unaffected. Auth BASELINES
        // stay the caller's choice: they are policy, not wiring.
        ZenitAuth.init(Datasources.getDefault());
        // The users/roles resources live in HohenheimPanel; zenit-auth's own
        // default panel would be a second registration for the same slug.
        AuthSettings.VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
        ServerSettings.VALUES.setValue(ServerSettings.Network.AUTO_START_HTTP, false);
        // The suite opts OUT explicitly: its sites have no system user of their own
        // and would otherwise all fault. WorkloadIdentityTest flips it back on.
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Process.REQUIRE_DEDICATED_USER, false);
        ServerZenitRuntime.init().join();
    }

    /**
     * Declare hohenheim's grantable models exactly once per JVM, before the boot stages.
     *
     * AIDEV-NOTE: zenit-auth's record-access page registry is a MODULES-stage SNAPSHOT, and
     * RecordAccessCoverage refuses -- loudly, as it should -- a model that becomes grantable
     * after the drain. Six test classes used to make this call themselves "before the boot
     * stages, exactly as ServerMain does", which was true only because each owned its JVM.
     * Sharing a JVM makes the SECOND caller the one that lands after the drain, so the
     * declaration belongs to the funnel that owns the boot, not to whichever class happens
     * to run first. Production keeps the loud guard; the harness declares once.
     */
    public static synchronized void declareAccessModelsOnce() {
        if (accessModelsDeclared) {
            return;
        }
        accessModelsDeclared = true;
        HohenheimAccess.declareGrantableModels();
    }

    /**
     * Give a database-free unit test a real control-plane database, and therefore a real
     * {@code ControllerIdentity}, without booting the whole runtime.
     *
     * AIDEV-NOTE: every daemon resource NAME is namespaced by the controller identity,
     * which lives in the database, so a test that names or labels anything needs one.
     * There is deliberately no ControllerIdentity test override: a fake token would be a
     * shared default by another name, which is the hazard the identity exists to remove.
     * An already-registered default is reused, so this never yanks a live server's
     * database out from under it.
     */
    public static synchronized void ensureDatasource() {
        if (Datasources.getDefault() != null) {
            return;
        }
        if (HohenheimDatabase.datasource() != null) {
            Datasources.register(Datasources.DEFAULT, HohenheimDatabase.datasource());
            return;
        }

        try {
            TestDatabases.freshDatabase();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create the Hohenheim test datasource", exception);
        }
    }
}
