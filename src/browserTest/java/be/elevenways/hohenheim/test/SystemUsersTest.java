package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Pins the uid-resolution contract: null means "inherit the daemon user", a configured
 * user must resolve, and uid 0 (root) is refused outright.
 */
class SystemUsersTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());

        SiteTypes.boot();
        HohenheimEndpoints.init();
        HohenheimDatabase.init();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");

        saveUser("hh-uid-test-user", 4242);
        saveUser("hh-uid-test-root", 0);
    }

    private static void saveUser(String name, int uid) {
        var model = Models.get(SystemUserModel.class);
        Row row = model.createEmptyRow();
        row.set(SystemUserModel.NAME, name);
        row.set(SystemUserModel.UID, uid);
        row.set(SystemUserModel.GID, uid);
        row.set(SystemUserModel.HOME, "/nonexistent");
        row.set(SystemUserModel.GECOS, "uid resolution test");
        row.set(SystemUserModel.OBSOLETE, false);
        row.set(SystemUserModel.LAST_SEEN_AT, Instant.now());
        model.save(row);
    }

    @Test
    void unsetUserResolvesToNull() {
        assertThat(SystemUsers.resolveUid(null)).isNull();
        assertThat(SystemUsers.resolveUid("")).isNull();
        assertThat(SystemUsers.resolveUid("   ")).isNull();
    }

    @Test
    void legacyNonPositiveIdResolvesToNull() {
        assertThat(SystemUsers.resolveUid(0)).isNull();
        assertThat(SystemUsers.resolveUid(-1)).isNull();
    }

    @Test
    void configuredUserResolvesToItsUid() {
        assertThat(SystemUsers.resolveUid("hohenheim:hh-uid-test-user")).isEqualTo(4242);
    }

    @Test
    void danglingUserFailsClosed() {
        assertThatThrownBy(() -> SystemUsers.resolveUid("hohenheim:hh-uid-test-missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void rootUserIsRefused() {
        assertThatThrownBy(() -> SystemUsers.resolveUid("hohenheim:hh-uid-test-root"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("root");
    }
}
