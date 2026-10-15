package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M014_StackPublishedPorts;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.stack.StackSpec;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationResult;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.migration.InstallsAt.withIntegrityMode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An install through M013 upgrades its stack services' {@code ports} lists onto the one published-port vocabulary:
 * each becomes the {@code published_ports} map a stack's next deploy would write, binds kept, and nothing else moves.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class StackPublishedPortsMigrationTest {

    private static final IntegerField ID = IntegerField.builder().name("id").build();
    private static final StringField NAME = StringField.builder().name("name").build();
    private static final StringField KIND = StringField.builder().name("kind").build();
    private static final SchemaField SETTINGS = SchemaField.builder("settings").build();

    /** One frozen model over these fields: a field joins one schema only. */
    private static final FrozenModel INSTANCES = new FrozenModel("instances", ID, NAME, KIND, SETTINGS);

    @Test
    void anInstallThroughM013PublishesItsStackPortsAsPublishedPorts() throws Exception {
        File file = File.createTempFile("hohenheim-stack-ports", ".db");
        file.delete();
        file.deleteOnExit();

        // 1. An install through M013 holding a stack service with a loopback TCP port, a whole-host UDP port and a
        //    half entry that never published anything, plus a container whose own settings carry a "ports" key.
        InstallsAt.migrateThrough(file.toPath(), "013");
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + file.getAbsolutePath());
        int service = instance(datasource, "hohenheim:stack_service", Map.of("image", "nginx", "ports", List.of(
            port(80, 8080, "tcp", "127.0.0.1"), port(51820, 51820, "udp", ""), port(443, null, "tcp", ""))));
        int container = instance(datasource, "hohenheim:docker_container",
            Map.of("image", "alpine", "ports", List.of(port(22, 2222, "tcp", ""))));

        // 2. The upgrade to M014 under integrity=fail applies M014 alone. It is bounded to 014 so a later migration
        //    neither changes what this step applies nor touches the rows the next steps read.
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult upgrade = new MigrationRunner(datasource,
                InstallsAt.discoveredThrough(datasource.getDatasourceIdentifier(), "014")).migrate();
            assertThat(upgrade.isSuccess()).as("step 2: the upgrade from 013 succeeds").isTrue();
            assertThat(upgrade.getResults().stream().filter(MigrationResult::isApplied)
                .map(MigrationResult::getVersion).toList()).as("step 2: it applies exactly M014")
                .containsExactly("014");
        });

        // 3. The service's ports are its published ports, named and spelled as the stack's lowering writes them, the
        //    half entry dropped; its other settings are untouched.
        Map<?, ?> migrated = settingsOf(datasource, service);
        assertThat(migrated.containsKey("ports")).as("step 3: the old list is gone").isFalse();
        assertThat(migrated.get("image")).as("step 3: its other settings stay").isEqualTo("nginx");
        Map<String, Object> asSettings = new LinkedHashMap<>();
        migrated.forEach((key, value) -> asSettings.put(String.valueOf(key), value));
        assertThat(PublishedPort.of(asSettings)).as("step 3: exactly what the lowering writes for its ports")
            .containsExactly(new StackSpec.PortSpec(80, 8080, "tcp", "127.0.0.1").published(),
                new StackSpec.PortSpec(51820, 51820, "udp", "").published());

        // 4. A container's own settings are not a stack service's: they stay as they were.
        assertThat(settingsOf(datasource, container).containsKey("ports"))
            .as("step 4: another kind's settings stay").isTrue();

        // 5. The inverse step restores the list it replaced (the half entry stays dropped: it published nothing).
        M014_StackPublishedPorts.unfoldStackPorts(datasource);
        assertThat(String.valueOf(settingsOf(datasource, service).get("ports"))).as("step 5: down() restores them")
            .isEqualTo(String.valueOf(List.of(port(80, 8080, "tcp", "127.0.0.1"), port(51820, 51820, "udp", ""))));
        datasource.close();
    }

    private static Map<String, Object> port(int container, Integer host, String protocol, String hostIp) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("container_port", container);
        port.put("host_port", host);
        port.put("protocol", protocol);
        port.put("host_ip", hostIp);
        return port;
    }

    private static int instance(SqliteDatasource datasource, String kind, Map<String, Object> settings) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            FrozenModel instances = instances();
            Row row = instances.createEmptyRow();
            row.set(NAME, "stack-ports-" + kind);
            row.set(KIND, kind);
            row.set(SETTINGS, settings);
            ids.add(instances.save(row).get(ID));
        });
        return ids.get(0);
    }

    private static Map<?, ?> settingsOf(SqliteDatasource datasource, int id) {
        List<Map<?, ?>> found = new ArrayList<>();
        Db.run(datasource, () -> {
            Row row = instances().find().where(ID.eq(id)).first();
            found.add(row.get(SETTINGS) instanceof Map<?, ?> map ? map : Map.of());
        });
        return found.get(0);
    }

    private static FrozenModel instances() {
        return INSTANCES;
    }
}
