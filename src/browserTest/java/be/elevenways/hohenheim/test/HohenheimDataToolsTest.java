package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.ai.server.mcp.host.McpCoverage;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data tools through the real MCP endpoint, as an administrator's key: an app using a database is not moved
 * away from it, a dump is restored by its code, a database's move routes by cleanup_only, a backup target is tested
 * and deleted, a snapshot's note is changed and the file tools refuse what they cannot do.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimDataToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-data-";

    private static final Pattern RUN_CODE = Pattern.compile("RUN\\d+");

    @Test
    void anAdministratorMovesRestoresAndTestsDataByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        AccessContext operator = TenantConduits.operator();
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");

            // 1. An app on the local host using a database there, and a second admitted host.
            int appId = record(InstanceModel.class, cleanUp, row -> {
                row.set(InstanceModel.NAME, PREFIX + "app-" + tag);
                row.set(InstanceModel.KIND, "hohenheim:docker_container");
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
                row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
                row.set(InstanceModel.HOST_ID, HostModel.localHostId());
            });
            int databaseId = record(DatabaseModel.class, cleanUp, row -> {
                row.set(DatabaseModel.NAME, PREFIX + "db-" + tag);
                row.set(DatabaseModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
                row.set(DatabaseModel.DB_NAME, "appdb");
                row.set(DatabaseModel.DB_USER, "appuser");
                row.set(DatabaseModel.DB_PASSWORD, "fixture-password-" + tag);
                row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_DEDICATED);
                row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
            });
            int linkId = record(InstanceDatabaseModel.class, cleanUp, row -> {
                row.set(InstanceDatabaseModel.INSTANCE_ID, appId);
                row.set(InstanceDatabaseModel.DATABASE_ID, databaseId);
                row.set(InstanceDatabaseModel.ENV_PREFIX, "DB");
            });
            int betaId = HostFixtures.admittedDockerHost(PREFIX + "beta-" + tag, 16L * 1024);
            cleanUp.push(() -> HardDeletes.byId(Models.get(HostModel.class), betaId));
            String appCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, appId, operator);
            String betaCode = HohenheimMcpTest.code(HostModel.MODEL_ID, betaId, operator);

            // 2. The move is refused by name while the app uses a database on its old host, and nothing moved.
            McpHttpSession.Answer refused = admin.call("app_move", Map.of("code", appCode, "host", betaCode));
            assertThat(refused.error()).as("step 2: " + refused.text()).isTrue();
            assertThat(refused.text()).as("step 2: the database is named").contains(PREFIX + "db-" + tag)
                .contains("Detach the database first");
            assertThat((Integer) Models.get(InstanceModel.class).findById(appId).get(InstanceModel.HOST_ID))
                .as("step 2: the app stayed").isEqualTo(HostModel.localHostId());

            // 3. Detached (delete DL), that reason is gone.
            String linkCode = HohenheimMcpTest.code(InstanceDatabaseModel.MODEL_ID, linkId, operator);
            McpHttpSession.Answer detached = admin.call("delete", Map.of("code", linkCode));
            assertThat(detached.error()).as("step 3: " + detached.text()).isFalse();
            assertThat(admin.call("app_move", Map.of("code", appCode, "host", betaCode)).text())
                .as("step 3: no database holds the app back").doesNotContain("Detach the database first");

            // 4. A database's move routes by cleanup_only: no earlier move left an engine to remove.
            String databaseCode = HohenheimMcpTest.code(DatabaseModel.MODEL_ID, databaseId, operator);
            McpHttpSession.Answer cleanup = admin.call("database_move_to_shared",
                Map.of("code", databaseCode, "cleanup_only", true));
            assertThat(cleanup.error()).as("step 4: nothing to clean up: " + cleanup.text()).isTrue();

            // 5. A complete dump of it: describe names the restore, a restore needs its new name, and in place takes
            //    none; with a name the restore starts as a run.
            int dumpId = record(DatabaseDumpModel.class, cleanUp, row -> {
                row.set(DatabaseDumpModel.DATABASE_NAME, PREFIX + "db-" + tag);
                row.set(DatabaseDumpModel.DATABASE_ID, databaseId);
                row.set(DatabaseDumpModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
                row.set(DatabaseDumpModel.STATUS, "complete");
                // A restorable dump names the committed key of its file (DatabaseDumpModel.isRestorable).
                row.set(DatabaseDumpModel.REMOTE_KEY, PREFIX + "dump-" + tag + ".sql.gz");
            });
            String dumpCode = HohenheimMcpTest.code(DatabaseDumpModel.MODEL_ID, dumpId, operator);
            assertThat(admin.call("describe", Map.of("code", dumpCode)).text()).as("step 5: describe names the restore")
                .contains("database_dump_restore code=" + dumpCode);
            assertThat(admin.call("database_dump_restore", Map.of("code", dumpCode)).error())
                .as("step 5: a restore into a new database needs its name").isTrue();
            McpHttpSession.Answer inPlaceNamed = admin.call("database_dump_restore",
                Map.of("code", dumpCode, "in_place", true, "name", "elsewhere"));
            assertThat(inPlaceNamed.error()).as("step 5: in place takes no name: " + inPlaceNamed.text()).isTrue();
            assertThat(inPlaceNamed.text()).as("step 5: the refusal names it").contains("name");
            McpHttpSession.Answer restored = admin.call("database_dump_restore",
                Map.of("code", dumpCode, "name", PREFIX + "restored-" + tag));
            assertThat(restored.error()).as("step 5: " + restored.text()).isFalse();
            Matcher run = RUN_CODE.matcher(restored.text());
            assertThat(run.find()).as("step 5: a run: " + restored.text()).isTrue();
            // No engine answers in this lane: the run ends (as it may), and only then is its database cleared.
            HohenheimMcpTest.followed(admin, run.group(), "step 5");
            cleanUp.push(() -> Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.NAME.eq(PREFIX + "restored-" + tag)).all()
                .forEach(row -> HardDeletes.row(Models.get(DatabaseModel.class), row)));

            // 6. A backup target on a folder of this machine is tested by its code.
            Path folder = Files.createTempDirectory("hh-data-tools");
            cleanUp.push(() -> folder.toFile().delete());
            int targetId = record(BackupTargetModel.class, cleanUp, row -> {
                row.set(BackupTargetModel.NAME, PREFIX + "target-" + tag);
                row.set(BackupTargetModel.KIND, "hohenheim:filesystem");
                row.set(BackupTargetModel.SETTINGS, Map.of("path", folder.toString()));
            });
            McpHttpSession.Answer tested = admin.call("test",
                Map.of("code", HohenheimMcpTest.code(BackupTargetModel.MODEL_ID, targetId, operator)));
            assertThat(tested.error()).as("step 6: " + tested.text()).isFalse();
            assertThat(tested.text()).as("step 6: it can be reached").contains("test: passed");

            // 7. A snapshot's note is changed by its code; a call naming neither a snapshot nor an app is refused, and
            //    an incomplete snapshot is not put back.
            int snapshotId = record(InstanceSnapshotModel.class, cleanUp, row -> {
                row.set(InstanceSnapshotModel.INSTANCE_ID, appId);
                row.set(InstanceSnapshotModel.STATUS, InstanceSnapshotModel.STATUS_FAILED);
            });
            String snapshotCode = HohenheimMcpTest.code(InstanceSnapshotModel.MODEL_ID, snapshotId, operator);
            McpHttpSession.Answer noted = admin.call("snapshot_set",
                Map.of("code", snapshotCode, "note", "before upgrade " + tag));
            assertThat(noted.error()).as("step 7: " + noted.text()).isFalse();
            assertThat(noted.text()).as("step 7: the snapshot's code").contains(snapshotCode);
            assertThat((String) Models.get(InstanceSnapshotModel.class).findById(snapshotId)
                .get(InstanceSnapshotModel.NOTE)).as("step 7: the note is kept").isEqualTo("before upgrade " + tag);
            assertThat(admin.call("snapshot_set", Map.of("note", "nowhere")).error())
                .as("step 7: neither a snapshot nor an app").isTrue();
            assertThat(admin.call("snapshot_restore", Map.of("code", snapshotCode)).error())
                .as("step 7: a failed snapshot is not put back").isTrue();

            // 7b. Every kind that belongs to an app lists by the app's code, a dump by its database's.
            McpHttpSession.Answer snapshots = admin.call("list", Map.of("kind", "snapshot", "of", appCode));
            assertThat(snapshots.error()).as("step 7b: " + snapshots.text()).isFalse();
            assertThat(snapshots.text()).as("step 7b: the app's snapshot").contains(snapshotCode);
            for (String kind : List.of("backup", "volume", "app_device")) {
                McpHttpSession.Answer listed = admin.call("list", Map.of("kind", kind, "of", appCode));
                assertThat(listed.error()).as("step 7b: " + kind + " of the app: " + listed.text()).isFalse();
            }
            assertThat(admin.call("list", Map.of("kind", "database_dump", "of", databaseCode)).text())
                .as("step 7b: the database's dump").contains(dumpCode);

            // 7c. Taking a snapshot of an app is the write that must not run twice: request applies to it too.
            Map<?, ?> snapshotArguments = (Map<?, ?>) McpCoverage.offered(server, operator).stream()
                .filter(tool -> tool.name().equals("snapshot_set")).findFirst().orElseThrow()
                .inputSchema(operator).get("properties");
            assertThat(String.valueOf(((Map<?, ?>) snapshotArguments.get("request")).get("description")))
                .as("step 7c: request is for every code snapshot_set takes").doesNotContain("Only for code");
            assertThat(admin.call("snapshot_set", Map.of("code", appCode)).text())
                .as("step 7c: an app without volumes, said as an app").doesNotContain("Instance");

            // 8. The file tools name the app's file calls; a folder takes no content (refused before any file is
            //    touched), and this stopped app without volumes has nothing to read.
            assertThat(admin.call("describe", Map.of("code", appCode)).text()).as("step 8: describe names them")
                .contains("file_read code=" + appCode).contains("file_write code=" + appCode);
            McpHttpSession.Answer folderText = admin.call("file_write",
                Map.of("code", appCode, "path", "/data/notes", "folder", true, "content", "text"));
            assertThat(folderText.error()).as("step 8: a folder takes no content: " + folderText.text()).isTrue();
            assertThat(folderText.text()).as("step 8: the refusal names it").contains("content");
            assertThat(admin.call("file_read", Map.of("code", appCode)).error())
                .as("step 8: no volume to read").isTrue();

            // 9. The backup target is deleted by its code.
            McpHttpSession.Answer targetGone = admin.call("delete",
                Map.of("code", HohenheimMcpTest.code(BackupTargetModel.MODEL_ID, targetId, operator)));
            assertThat(targetGone.error()).as("step 9: " + targetGone.text()).isFalse();
            assertThat(Models.get(BackupTargetModel.class).findById(targetId)).as("step 9: it is gone").isNull();
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
            local.restore();
        }
    }

    /** @return the id of a row saved with these values, deleted again at the end */
    static int record(Class<? extends Model> model, Deque<Runnable> cleanUp, Consumer<Row> fill) {
        Row row = Models.get(model).createEmptyRow();
        fill.accept(row);
        Models.get(model).save(row);
        int id = (Integer) Objects.requireNonNull(Models.get(model).getPrimaryKeyValue(row));
        cleanUp.push(() -> HardDeletes.byId(Models.get(model), id));
        return id;
    }
}
