package be.elevenways.hohenheim.test;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.NodeVersionModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.hohenheim.server.task.UpdateNodeVersions;
import be.elevenways.hohenheim.server.task.UpdateSystemUsers;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Verifies the UpdateSystemUsers / UpdateNodeVersions tasks reconcile discovered
 * host state with the backing tables — including obsolete-marking of entries that
 * no longer exist.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiscoveryTaskTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;

        UpstreamKindHandlers.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    @Test
    @Order(1)
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
    @Order(2)
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
        ghost.set(SystemUserModel.LAST_SEEN_AT, Instant.now());
        model.save(ghost);

        UpdateSystemUsers.reconcile();

        Row reloaded = model.find()
            .where(SystemUserModel.NAME.eq("hh-phantom-user-does-not-exist"))
            .first();
        assertThat(reloaded).describedAs("ghost row should still exist after reconciliation").isNotNull();
        assertThat((Boolean) reloaded.get(SystemUserModel.OBSOLETE))
            .describedAs("unseen rows should be marked obsolete, not deleted").isTrue();
    }

    @Test
    @Order(3)
    void updateNodeVersionsReconcilesGhostEntry() {
        var model = Models.get(NodeVersionModel.class);

        Row ghost = model.createEmptyRow();
        ghost.set(NodeVersionModel.VERSION, "0.0.0-phantom");
        ghost.set(NodeVersionModel.PATH, "/nonexistent/bin/node-phantom");
        ghost.set(NodeVersionModel.SOURCE, "test");
        ghost.set(NodeVersionModel.OBSOLETE, false);
        ghost.set(NodeVersionModel.LAST_SEEN_AT, Instant.now());
        model.save(ghost);

        UpdateNodeVersions.reconcile();

        Row reloaded = model.find()
            .where(NodeVersionModel.PATH.eq("/nonexistent/bin/node-phantom"))
            .first();
        assertThat(reloaded).describedAs("ghost node version should still exist").isNotNull();
        assertThat((Boolean) reloaded.get(NodeVersionModel.OBSOLETE))
            .describedAs("unseen node versions should be marked obsolete").isTrue();
    }

    @Test
    @Order(4)
    void updateNodeVersionsScansConfiguredNLocations() throws Exception {
        Path root = Files.createTempDirectory("hohenheim-node-location");
        Path node = root.resolve("v99.4.2/bin/node");
        Files.createDirectories(node.getParent());
        Files.writeString(node, "#!/bin/sh\nexit 0\n");
        assertThat(node.toFile().setExecutable(true)).isTrue();

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Node.LOCATIONS, root.toString());
        try {
            UpdateNodeVersions.reconcile();
            Row discovered = Models.get(NodeVersionModel.class).find()
                .where(NodeVersionModel.PATH.eq(node.toString()))
                .first();
            assertThat(discovered).isNotNull();
            assertThat(discovered.get(NodeVersionModel.VERSION)).isEqualTo("99.4.2");
            assertThat(discovered.get(NodeVersionModel.SOURCE)).isEqualTo("configured");
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Node.LOCATIONS, null);
        }
    }
}
