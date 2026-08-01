package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
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

    private HohenheimTestRuntime() {
    }

    public static void ensureBooted() {
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

    private static synchronized void ensureDatasource() {
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
