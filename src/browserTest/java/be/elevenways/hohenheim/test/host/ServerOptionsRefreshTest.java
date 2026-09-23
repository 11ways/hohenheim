package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.options.ServerOptions;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live server-option registry follows the host records without ever being emptied.
 *
 * AIDEV-NOTE: refresh used to clear the registry and refill it, so a form render racing a
 * host save read an empty registry and offered no host. It now overwrites, then prunes only
 * the ids it published itself; this pins that a surviving host is never removed and a
 * deleted one is.
 */
class ServerOptionsRefreshTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void refreshKeepsLiveHostsAndPrunesOnlyRemovedOnes() {
        Db.run(datasource, () -> {
            ServerModel model = Models.get(ServerModel.class);
            int keptId = host(model, "options-kept");
            int goneId = host(model, "options-gone");

            // 1. Both hosts are offered after a refresh.
            ServerOptions.refresh();
            assertThat(ServerOptions.REGISTRY.get(key(keptId)))
                .as("step 1: the first host is offered").isNotNull();
            assertThat(ServerOptions.REGISTRY.get(key(goneId)))
                .as("step 1: the second host is offered").isNotNull();

            // 2. A deleted host disappears on the next refresh; the surviving one stays.
            model.delete(goneId);
            ServerOptions.refresh();
            assertThat(ServerOptions.REGISTRY.get(key(goneId)))
                .as("step 2: the deleted host is pruned").isNull();
            assertThat(ServerOptions.REGISTRY.get(key(keptId)).getDisplayName())
                .as("step 2: the surviving host is still offered")
                .startsWith("options-kept");
        });
    }

    private static int host(ServerModel model, String name) {
        Row row = model.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        model.save(row);
        return row.get(ServerModel.ID);
    }

    private static Identifier key(int serverId) {
        return Identifier.of("hohenheim", String.valueOf(serverId));
    }
}
