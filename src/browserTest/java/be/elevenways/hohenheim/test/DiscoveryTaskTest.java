package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.task.UpdateSystemUsers;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;


/**
 * Verifies the UpdateSystemUsers task reconciles discovered host state with the
 * system_users table -- including obsolete-marking of entries that no longer exist.
 */
class DiscoveryTaskTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    @Test
    void updateSystemUsersPopulatesTable() {
        UpdateSystemUsers.reconcile();

        var model = Models.get(SystemUserModel.class);
        long count = model.find().count();

        // /etc/passwd always has at least 'root' — if this fails on a weird CI
        // sandbox, we'll need to skip conditionally. For now this is a clear signal.
        assertThat(count).describedAs("system_users populated from /etc/passwd").isGreaterThan(0);

        // And none should be obsolete on the first run
        long obsoleteCount = model.find().where(SystemUserModel.OBSOLETE.eq(true)).count();
        assertThat(obsoleteCount).isEqualTo(0);
    }

    @Test
    void updateSystemUsersMarksUnseenRowsObsolete() {
        var model = Models.get(SystemUserModel.class);

        // Insert a ghost user that definitely isn't in /etc/passwd
        Row ghost = model.createEmptyRow();
        ghost.set(SystemUserModel.NAME, "hh-phantom-user-does-not-exist");
        ghost.set(SystemUserModel.UID, 99999);
        ghost.set(SystemUserModel.GID, 99999);
        ghost.set(SystemUserModel.HOME, "/nonexistent");
        ghost.set(SystemUserModel.GECOS, "ghost");
        ghost.set(SystemUserModel.OBSOLETE, false);
        ghost.set(SystemUserModel.LAST_SEEN_AT, Now.instant());
        model.save(ghost);

        try {
            UpdateSystemUsers.reconcile();

            Row reloaded = model.find()
                .where(SystemUserModel.NAME.eq("hh-phantom-user-does-not-exist"))
                .first();
            assertThat(reloaded).describedAs("ghost row should still exist after reconciliation").isNotNull();
            assertThat((Boolean) reloaded.get(SystemUserModel.OBSOLETE))
                .describedAs("unseen rows should be marked obsolete, not deleted").isTrue();
        } finally {
            // The obsolete ghost must not outlive this test: the populate test asserts that
            // a reconcile of real host state marks NOTHING obsolete, in whatever order.
            model.delete(ghost);
        }
    }
}
