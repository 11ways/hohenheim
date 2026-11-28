package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.migration.M008_StreamPortLists;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.ports.StreamPort;
import be.elevenways.hohenheim.ports.StreamProtocol;
import be.elevenways.hohenheim.server.upstream.kinds.StreamUpstreamKind;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The live upgrade from the published M007 gives every TCP/UDP stream its single public port as a list of one, so a
 * mail server's five single-port streams stay five sites, each forwarding exactly as before; the rollback gives each
 * its single port back, and refuses while a stream forwards several.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class StreamPortListsMigrationTest {

    /** The five streams of a mail server as the live host stores them, and a website beside them. */
    private static final Map<Integer, String> STORED = new LinkedHashMap<>();

    static {
        STORED.put(1, "{\"listen_port\":25,\"forward_host\":\"127.0.0.1\",\"forward_port\":2525,\"protocol\":\"tcp\","
            + "\"connect_timeout\":10,\"proxy_protocol_v2\":true}");
        STORED.put(2, "{\"listen_port\":465,\"forward_host\":\"127.0.0.1\",\"forward_port\":4465,"
            + "\"proxy_protocol_v2\":true}");
        STORED.put(3, "{\"listen_port\":587,\"forward_host\":\"127.0.0.1\",\"forward_port\":4587,\"protocol\":\"tcp\","
            + "\"connect_timeout\":30,\"proxy_protocol_v2\":false}");
        STORED.put(4, "{\"listen_port\":143,\"forward_host\":\"127.0.0.1\",\"protocol\":\"tcp\"}");
        STORED.put(5, "{\"listen_port\":993,\"forward_host\":\"127.0.0.1\",\"forward_port\":4993,"
            + "\"protocol\":\"both\"}");
    }

    @Test
    void theUpgradeGivesEveryStreamItsPortAsAListOfOneAndTheRollbackGivesItBack() throws Exception {
        String version = new M008_StreamPortLists().getVersion();
        File db = File.createTempFile("hohenheim-stream-port-lists", ".db");
        db.delete();
        db.deleteOnExit();

        // 1. A published install holds M001 to M007 and the five streams (the fourth in the trash), and a website.
        InstallsAt.migrateBelow(db.toPath(), version);
        SqliteDatasource datasource = new SqliteDatasource(TestSqliteFiles.url(db.toPath()));
        try {
            Map<Integer, List<PublishedPort>> before = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> stored : STORED.entrySet()) {
                datasource.rawUpdate("INSERT INTO sites (id, name, slug, upstream_kind, enabled, settings, deleted_at)"
                    + " VALUES (" + stored.getKey() + ", 'duiventil " + stored.getKey() + "', 'duiventil-"
                    + stored.getKey() + "', 'hohenheim:stream', 1, '" + stored.getValue() + "', "
                    + (stored.getKey() == 4 ? "'2026-11-25T10:00:00Z'" : "NULL") + ")");
                before.put(stored.getKey(), claimsBefore(parse(stored.getValue())));
            }
            datasource.rawUpdate("INSERT INTO sites (id, name, slug, upstream_kind, enabled, settings) VALUES (6,"
                + " 'shop', 'shop', 'hohenheim:static', 1, '{\"root_path\":\"/srv/shop\"}')");

            // 2. The upgrade, under the strict integrity posture the hosts run, applies M008 and what came after it;
            //    those later ones are rolled back, so the steps below read the schema M008 leaves.
            InstallsAt.Upgrade upgrade = InstallsAt.upgradeTo(datasource, version);
            assertThat(upgrade.applied()).as("step 2: the upgrade applies M008 and its successors")
                .isEqualTo(1 + upgrade.later());

            // 3. Five sites still, each a list of one: its public port, where it goes and how, unchanged; what every
            //    port shares stays on the site, and the website is untouched.
            StreamUpstreamKind kind = new StreamUpstreamKind();
            for (int id = 1; id <= 5; id++) {
                Map<String, Object> settings = settings(datasource, id);
                assertThat(StreamPort.of(settings)).as("step 3: site %s holds one port", id).hasSize(1);
                assertThat(settings).as("step 3: site %s keeps no single-port key", id)
                    .doesNotContainKeys("listen_port", "forward_port", "protocol", "proxy_protocol_v2")
                    .containsEntry("forward_host", "127.0.0.1");
                assertThat(kind.proxyPorts(settings)).as("step 3: site %s listens and forwards as before", id)
                    .isEqualTo(before.get(id));
            }
            assertThat(StreamPort.of(settings(datasource, 1)).getFirst())
                .as("step 3: the PROXY v2 header stays on the port that sent it")
                .isEqualTo(new StreamPort(25, 2525, StreamProtocol.TCP, true));
            assertThat(StreamPort.of(settings(datasource, 4)).getFirst())
                .as("step 3: a port without its own upstream port forwards to the same number, over TCP")
                .isEqualTo(new StreamPort(143, 143, StreamProtocol.TCP, false));
            assertThat(String.valueOf(settings(datasource, 3).get("connect_timeout")))
                .as("step 3: the connect timeout stays the site's").isEqualTo("30");
            assertThat(((Number) datasource.rawQuery("SELECT COUNT(*) AS c FROM sites").get(0).get("c")).longValue())
                .as("step 3: no site was merged or made").isEqualTo(6L);

            // 4. The rollback gives every stream its single port back, as it was stored.
            new MigrationRunner(datasource).rollback(List.of(MigrationKey.of(new M008_StreamPortLists())))
                .requireSuccess();
            for (int id = 1; id <= 5; id++) {
                assertThat(claimsBefore(settings(datasource, id))).as("step 4: site %s has its single port back", id)
                    .isEqualTo(before.get(id));
                assertThat(settings(datasource, id)).as("step 4: site %s holds no list", id).doesNotContainKey("ports");
            }

            // 5. Upgraded again, a stream that came to forward two ports keeps the rollback from guessing a split.
            InstallsAt.upgradeTo(datasource, version);
            datasource.rawUpdate("UPDATE sites SET settings = '{\"forward_host\":\"127.0.0.1\",\"ports\":"
                + "{\"25\":\"2525/tcp proxy-v2\",\"465\":\"4465/tcp proxy-v2\"}}' WHERE id = 1");
            Throwable refused = catchThrowable(() -> new MigrationRunner(datasource)
                .rollback(List.of(MigrationKey.of(new M008_StreamPortLists()))).requireSuccess());
            StringBuilder said = new StringBuilder();
            for (Throwable cause = refused; cause != null; cause = cause.getCause()) said.append(cause).append('\n');
            assertThat(said.toString()).as("step 5: the rollback names the stream of several ports")
                .contains("duiventil 1").contains("25, 465");
        } finally {
            datasource.close();
        }
    }

    /** @return the claims and forwards of a stream as the single-port shape spelled them, the relay's own rule */
    private static List<PublishedPort> claimsBefore(Map<String, Object> single) {
        int listen = ((Number) single.get("listen_port")).intValue();
        int forward = single.get("forward_port") instanceof Number number ? number.intValue() : listen;
        StreamProtocol protocol = single.get("protocol") == null ? StreamProtocol.TCP
            : StreamProtocol.ofToken(single.get("protocol"));
        return protocol.protocols().stream()
            .map(transport -> new PublishedPort(transport, listen, forward, transport, false)).toList();
    }

    private static Map<String, Object> settings(SqliteDatasource datasource, int id) {
        return parse(String.valueOf(datasource.rawQuery("SELECT settings FROM sites WHERE id = " + id).get(0)
            .get("settings")));
    }

    private static Map<String, Object> parse(String json) {
        return RawValues.map(RawValues.storedMapCopy(json));
    }
}
