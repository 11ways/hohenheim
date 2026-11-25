package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.RecordCodes;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database tier and backup tools through the real MCP endpoint, as an administrator's key: an engine and a
 * database created and changed, a password that goes in and never comes out, a database attached to an app and
 * detached, a backup target created, changed, tested and deleted, and a backup restore routed by in_place.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimDatabaseToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-db-";

    @Test
    void anAdministratorSetsDatabasesAndBackupTargetsByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");

            // 1. A shared engine is created by name and kind; its ceiling changes, its name does not.
            McpHttpSession.Answer engine = admin.call("database_engine_set", Map.of("name", PREFIX + "engine-" + tag,
                "engine", DatabaseModel.ENGINE_POSTGRES, "memory_limit_mb", 256));
            assertThat(engine.error()).as("step 1: " + engine.text()).isFalse();
            String engineCode = codeIn(engine.text(), "DE");
            int engineId = keyOf(DatabaseEngineModel.MODEL_ID, engineCode, cleanUp);
            McpHttpSession.Answer resized = admin.call("database_engine_set",
                Map.of("code", engineCode, "memory_limit_mb", 512));
            assertThat(resized.error()).as("step 1: " + resized.text()).isFalse();
            assertThat(resized.text()).as("step 1: what changed").contains(engineCode).contains("memory_limit_mb");
            assertThat((Integer) row(DatabaseEngineModel.MODEL_ID, engineId).get(DatabaseEngineModel.MEMORY_LIMIT_MB))
                .as("step 1: the new ceiling").isEqualTo(512);
            McpHttpSession.Answer renamed = admin.call("database_engine_set",
                Map.of("code", engineCode, "name", "elsewhere"));
            assertThat(renamed.error()).as("step 1: a name is fixed: " + renamed.text()).isTrue();
            assertThat(renamed.text()).as("step 1: the refusal names it").contains("name");

            // 2. A create without its engine says what it needs; a dedicated database is created with a password that
            //    no answer repeats.
            McpHttpSession.Answer lacking = admin.call("database_set", Map.of("name", PREFIX + "lacking-" + tag));
            assertThat(lacking.error()).as("step 2: " + lacking.text()).isTrue();
            assertThat(lacking.text()).as("step 2: what a create needs").contains("name and engine");
            String password = "fixture-password-" + tag;
            McpHttpSession.Answer created = admin.call("database_set", Map.of("name", PREFIX + "db-" + tag,
                "engine", DatabaseModel.ENGINE_POSTGRES, "placement", DatabaseModel.PLACEMENT_DEDICATED,
                "db_name", "appdb", "password", password, "memory_limit_mb", 256));
            assertThat(created.error()).as("step 2: " + created.text()).isFalse();
            assertThat(created.text()).as("step 2: the password never comes out").doesNotContain(password);
            String databaseCode = codeIn(created.text(), "DB");
            int databaseId = keyOf(DatabaseModel.MODEL_ID, databaseCode, cleanUp);
            assertThat((String) row(DatabaseModel.MODEL_ID, databaseId).get(DatabaseModel.DB_PASSWORD))
                .as("step 2: the password given is the one stored").isEqualTo(password);
            assertThat(admin.call("read", Map.of("codes", List.of(databaseCode))).text())
                .as("step 2: a read shows no password").doesNotContain(password);

            // 3. Its ceiling changes; a password and a generated one at once are refused by name, and a password
            //    change runs the Change password button's availability (a database that does not serve yet).
            McpHttpSession.Answer ceiling = admin.call("database_set",
                Map.of("code", databaseCode, "memory_limit_mb", 384));
            assertThat(ceiling.error()).as("step 3: " + ceiling.text()).isFalse();
            assertThat((Integer) row(DatabaseModel.MODEL_ID, databaseId).get(DatabaseModel.MEMORY_LIMIT_MB))
                .as("step 3: the new ceiling").isEqualTo(384);
            McpHttpSession.Answer twice = admin.call("database_set", Map.of("code", databaseCode,
                "password", "another-password-" + tag, "generate_password", true));
            assertThat(twice.error()).as("step 3: " + twice.text()).isTrue();
            assertThat(twice.text()).as("step 3: the refusal names it").contains("generate_password");
            McpHttpSession.Answer notServing = admin.call("database_set", Map.of("code", databaseCode,
                "password", "another-password-" + tag));
            assertThat(notServing.error()).as("step 3: not serving: " + notServing.text()).isTrue();
            assertThat(notServing.text()).as("step 3: the refusal names it").contains("password")
                .doesNotContain("another-password-" + tag);
            McpHttpSession.Answer frozen = admin.call("database_set", Map.of("code", databaseCode, "db_user", "x"));
            assertThat(frozen.error()).as("step 3: credentials are fixed: " + frozen.text()).isTrue();
            assertThat(frozen.text()).as("step 3: the refusal names it").contains("db_user");

            // 4. The database is attached to an app on its host, its prefix changed, then detached by delete.
            int appId = HohenheimDataToolsTest.record(InstanceModel.class, cleanUp, row -> {
                row.set(InstanceModel.NAME, PREFIX + "app-" + tag);
                row.set(InstanceModel.KIND, "hohenheim:docker_container");
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
                row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
                row.set(InstanceModel.HOST_ID, HostModel.localHostId());
            });
            String appCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, appId, TenantConduits.operator());
            McpHttpSession.Answer attached = admin.call("app_database_set",
                Map.of("app", appCode, "database", databaseCode, "env_prefix", "MAIN"));
            assertThat(attached.error()).as("step 4: " + attached.text()).isFalse();
            String linkCode = codeIn(attached.text(), "DL");
            int linkId = keyOf(InstanceDatabaseModel.MODEL_ID, linkCode, cleanUp);
            McpHttpSession.Answer prefixed = admin.call("app_database_set", Map.of("code", linkCode, "env_prefix",
                "SHOP"));
            assertThat(prefixed.error()).as("step 4: " + prefixed.text()).isFalse();
            assertThat((String) row(InstanceDatabaseModel.MODEL_ID, linkId).get(InstanceDatabaseModel.ENV_PREFIX))
                .as("step 4: the new prefix").isEqualTo("SHOP");
            McpHttpSession.Answer moved = admin.call("app_database_set", Map.of("code", linkCode, "app", appCode));
            assertThat(moved.error()).as("step 4: an attachment never moves: " + moved.text()).isTrue();
            assertThat(moved.text()).as("step 4: the refusal names it").contains("app");
            assertThat(admin.call("describe", Map.of("code", databaseCode)).text())
                .as("step 4: describe names the change").contains("database_set code=" + databaseCode);
            McpHttpSession.Answer detached = admin.call("delete", Map.of("code", linkCode));
            assertThat(detached.error()).as("step 4: " + detached.text()).isFalse();
            assertThat(Models.get(InstanceDatabaseModel.class).findById(linkId)).as("step 4: detached").isNull();

            // 5. A backup target on a folder of this machine is created, its folder changed, tested and deleted.
            Path folder = Files.createTempDirectory("hh-db-tools");
            Path other = Files.createTempDirectory("hh-db-tools-other");
            cleanUp.push(() -> folder.toFile().delete());
            cleanUp.push(() -> other.toFile().delete());
            McpHttpSession.Answer target = admin.call("backup_target_set", Map.of("name", PREFIX + "target-" + tag,
                "kind", "filesystem", "path", folder.toString()));
            assertThat(target.error()).as("step 5: " + target.text()).isFalse();
            String targetCode = codeIn(target.text(), "BT");
            int targetId = keyOf(BackupTargetModel.MODEL_ID, targetCode, cleanUp);
            assertThat(String.valueOf(row(BackupTargetModel.MODEL_ID, targetId).get(BackupTargetModel.KIND)))
                .as("step 5: the word is stored as its kind").isEqualTo("hohenheim:filesystem");
            McpHttpSession.Answer spelled = admin.call("backup_target_set", Map.of("name", PREFIX + "spelled-" + tag,
                "kind", "hohenheim:filesystem", "path", folder.toString()));
            assertThat(spelled.error()).as("step 5: a stored key is no word: " + spelled.text()).isTrue();
            assertThat(spelled.text()).as("step 5: the refusal names it").contains("kind");
            McpHttpSession.Answer repointed = admin.call("backup_target_set",
                Map.of("code", targetCode, "path", other.toString()));
            assertThat(repointed.error()).as("step 5: " + repointed.text()).isFalse();
            assertThat(String.valueOf(((Map<?, ?>) row(BackupTargetModel.MODEL_ID, targetId)
                .get(BackupTargetModel.SETTINGS)).get("path"))).as("step 5: the new folder")
                .isEqualTo(other.toString());
            McpHttpSession.Answer tested = admin.call("test", Map.of("code", targetCode));
            assertThat(tested.text()).as("step 5: it can be reached").contains("test: passed");

            // 6. A backup restore routes by in_place: a host is for a new app only, and an incomplete backup is not
            //    restored.
            int backupId = HohenheimDataToolsTest.record(InstanceBackupModel.class, cleanUp, row -> {
                row.set(InstanceBackupModel.INSTANCE_ID, appId);
                row.set(InstanceBackupModel.STATUS, InstanceBackupModel.STATUS_FAILED);
            });
            String backupCode = HohenheimMcpTest.code(InstanceBackupModel.MODEL_ID, backupId,
                TenantConduits.operator());
            String hostCode = HohenheimMcpTest.code(HostModel.MODEL_ID, HostModel.localHostId(),
                TenantConduits.operator());
            McpHttpSession.Answer hostInPlace = admin.call("backup_restore",
                Map.of("code", backupCode, "in_place", true, "host", hostCode));
            assertThat(hostInPlace.error()).as("step 6: " + hostInPlace.text()).isTrue();
            assertThat(hostInPlace.text()).as("step 6: the refusal names it").contains("host");
            assertThat(admin.call("backup_restore", Map.of("code", backupCode, "host", hostCode)).error())
                .as("step 6: a failed backup is not restored").isTrue();

            // 7. The target is deleted by its code.
            McpHttpSession.Answer targetGone = admin.call("delete", Map.of("code", targetCode));
            assertThat(targetGone.error()).as("step 7: " + targetGone.text()).isFalse();
            assertThat(Models.get(BackupTargetModel.class).findById(targetId)).as("step 7: it is gone").isNull();
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
            local.restore();
        }
    }

    /** @return the first code of the kind an answer names */
    private static String codeIn(String text, String prefix) {
        Matcher code = Pattern.compile("\\b" + prefix + "\\d+\\b").matcher(text);
        assertThat(code.find()).as("a " + prefix + " code in: " + text).isTrue();
        return code.group();
    }

    /** @return the key of the record a code names, deleted again at the end */
    private static int keyOf(Identifier model, String code, Deque<Runnable> cleanUp) {
        int id = Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(model, code), code));
        cleanUp.push(() -> HardDeletes.byId(Models.get(model), id));
        return id;
    }

    private static Row row(Identifier model, int id) {
        return Objects.requireNonNull(Models.get(model).findById(id), "record " + id);
    }
}
