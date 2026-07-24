package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.HohenheimDatabase;
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
        ServerSettings.VALUES.setValue(ServerSettings.Network.AUTO_START_HTTP, false);
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
            File db = File.createTempFile("hohenheim-runtime-test", ".db");
            db.deleteOnExit();
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
            HohenheimDatabase.init();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create the Hohenheim test datasource", exception);
        }
    }
}
