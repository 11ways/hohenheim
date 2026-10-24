package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.migration.M002_AfterBaseline;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.migration.MigrationKey;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.test.support.TestSqliteFiles;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live upgrade from the published baseline gives the Minecraft servers made from the starters before the status
 * probe existed their probe, leaves every other instance and every operator's choice alone, and its rollback takes
 * back exactly what it gave.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StatusProbeBackfillMigrationTest {

    private static final String PROBE = StatusProbe.MINECRAFT_JAVA.token();

    /** A published game port, as an instance made from a starter that players connect to carries it. */
    private static final String GAME_PORT = "\"published_ports\":{\"game\":\"30001:25565/tcp\"}";

    @Test
    void theUpgradeGivesTheStarterServersTheirProbeAndTheRollbackTakesBackOnlyThat() throws Exception {
        String version = new M002_AfterBaseline().getVersion();
        File db = File.createTempFile("hohenheim-status-probe-backfill", ".db");
        db.delete();
        db.deleteOnExit();

        // 1. A published install holds starters still named as before GameTemplateSeeder renamed them (migrations
        //    run before seeders), an operator's copy of one, and instances of each.
        InstallsAt.migrateBelow(db.toPath(), version);
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
        try {
            String starter = InstanceTemplateModel.SOURCE_STARTER;
            template(datasource, 1, "Minecraft (Paper)", starter);
            template(datasource, 2, "Velocity proxy", starter);
            template(datasource, 3, "Minecraft server", null);
            template(datasource, 4, "WordPress", starter);
            template(datasource, 5, "Minecraft server (Paper)", starter);
            instance(datasource, 10, 1, "{\"image\":\"itzg/minecraft-server\"," + GAME_PORT + "}");
            instance(datasource, 11, 2, "{\"image\":\"dockcenter/velocity\"," + GAME_PORT + "}");
            instance(datasource, 12, 1, "{\"image\":\"itzg/minecraft-server\"," + GAME_PORT
                + ",\"status_probe\":\"\"}");
            instance(datasource, 13, 3, "{\"image\":\"itzg/minecraft-server\"," + GAME_PORT + "}");
            instance(datasource, 14, 4, "{\"image\":\"wordpress\"}");
            instance(datasource, 15, 1, "{\"image\":\"itzg/minecraft-server\"," + GAME_PORT
                + ",\"status_probe\":\"" + PROBE + "\"}");
            instance(datasource, 16, 5, "{\"image\":\"itzg/minecraft-server\"}");

            // 2. The upgrade applies the one migration after the baseline.
            assertThat(new MigrationRunner(datasource).migrate().requireSuccess().getAppliedCount())
                .as("step 2: the upgrade applies one migration").isEqualTo(1);

            // 3. The starter servers without a probe got it, whatever their template is named; a cleared probe, an
            //    operator's template, another starter, the behind-Velocity backend (no game port of its own, its
            //    template named like the standalone server's family before the rename) and a server that already
            //    declared it are left as they were.
            assertThat(probeOf(datasource, 10)).as("step 3: the standalone starter's server").isEqualTo(PROBE);
            assertThat(probeOf(datasource, 11)).as("step 3: the proxy starter's server").isEqualTo(PROBE);
            assertThat(settingsOf(datasource, 12)).as("step 3: a cleared probe stays cleared")
                .containsEntry(StatusProbe.SETTING, "");
            assertThat(settingsOf(datasource, 13)).as("step 3: an operator's template is not a starter")
                .doesNotContainKey(StatusProbe.SETTING);
            assertThat(settingsOf(datasource, 14)).as("step 3: another starter's instance")
                .doesNotContainKey(StatusProbe.SETTING);
            assertThat(settingsOf(datasource, 16)).as("step 3: the backend players never reach directly")
                .doesNotContainKey(StatusProbe.SETTING);
            assertThat(settingsOf(datasource, 10)).as("step 3: the rest of the settings is kept")
                .containsEntry("image", "itzg/minecraft-server").containsKey("published_ports");
            assertThat(ledger(datasource)).as("step 3: the ledger names exactly the servers it wrote")
                .containsExactly(10, 11);

            // 4. The rollback takes the probe back from those two alone, and drops its ledger.
            new MigrationRunner(datasource).rollback(MigrationKey.of(HohenheimMigration.STREAM, version))
                .requireSuccess();
            assertThat(settingsOf(datasource, 10)).as("step 4: taken back from the standalone server")
                .doesNotContainKey(StatusProbe.SETTING).containsEntry("image", "itzg/minecraft-server");
            assertThat(settingsOf(datasource, 11)).as("step 4: taken back from the proxy server")
                .doesNotContainKey(StatusProbe.SETTING);
            assertThat(probeOf(datasource, 15)).as("step 4: a probe the backfill did not give stays")
                .isEqualTo(PROBE);
            assertThat(settingsOf(datasource, 12)).as("step 4: the cleared probe is untouched")
                .containsEntry(StatusProbe.SETTING, "");
            assertThat(((Number) datasource.rawQuery("SELECT COUNT(*) AS c FROM sqlite_master WHERE type = 'table'"
                + " AND name = 'status_probe_backfills'").get(0).get("c")).longValue())
                .as("step 4: the ledger is dropped").isZero();
        } finally {
            datasource.close();
        }
    }

    private static void template(SqliteDatasource datasource, int id, String name, String source) {
        datasource.rawUpdate("INSERT INTO instance_templates (id, name, source) VALUES (" + id + ", '" + name + "', "
            + (source == null ? "NULL" : "'" + source + "'") + ")");
    }

    private static void instance(SqliteDatasource datasource, int id, int templateId, String settings) {
        datasource.rawUpdate("INSERT INTO instances (id, name, kind, template_id, settings) VALUES (" + id
            + ", 'server " + id + "', 'hohenheim:docker_container', " + templateId + ", '" + settings + "')");
    }

    private static Object probeOf(SqliteDatasource datasource, int id) {
        return settingsOf(datasource, id).get(StatusProbe.SETTING);
    }

    private static Map<String, Object> settingsOf(SqliteDatasource datasource, int id) {
        Object stored = datasource.rawQuery("SELECT settings FROM instances WHERE id = " + id).get(0).get("settings");
        Object tree = stored instanceof String text ? Zenit.DRY.parseTree(text) : stored;
        Map<String, Object> settings = new LinkedHashMap<>();
        if (tree instanceof Map<?, ?> map) {
            map.forEach((key, value) -> settings.put(String.valueOf(key), value));
        }
        return settings;
    }

    private static List<Integer> ledger(SqliteDatasource datasource) {
        return datasource.rawQuery("SELECT instance_id FROM status_probe_backfills ORDER BY instance_id").stream()
            .map(row -> ((Number) row.get("instance_id")).intValue()).toList();
    }
}
