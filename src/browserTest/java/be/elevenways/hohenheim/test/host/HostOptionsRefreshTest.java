package be.elevenways.hohenheim.test.host;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.server.options.HostOptions;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.HostModel;
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
class HostOptionsRefreshTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
    }

    @Test
    void refreshKeepsLiveHostsAndPrunesOnlyRemovedOnes() {
        Db.run(datasource, () -> {
            HostModel model = Models.get(HostModel.class);
            int keptId = host(model, "options-kept");
            int goneId = host(model, "options-gone");

            // 1. Both hosts are offered after a refresh.
            HostOptions.refresh();
            assertThat(HostOptions.REGISTRY.get(key(keptId)))
                .as("step 1: the first host is offered").isNotNull();
            assertThat(HostOptions.REGISTRY.get(key(goneId)))
                .as("step 1: the second host is offered").isNotNull();

            // 2. A deleted host disappears on the next refresh; the surviving one stays.
            model.delete(goneId);
            HostOptions.refresh();
            assertThat(HostOptions.REGISTRY.get(key(goneId)))
                .as("step 2: the deleted host is pruned").isNull();
            assertThat(HostOptions.REGISTRY.get(key(keptId)).getDisplayName())
                .as("step 2: the surviving host is still offered")
                .startsWith("options-kept");
        });
    }

    private static int host(HostModel model, String name) {
        Row row = model.createEmptyRow();
        row.set(HostModel.NAME, name);
        row.set(HostModel.RUNTIME, HostModel.RUNTIME_DOCKER);
        row.set(HostModel.MODE, HostModel.MODE_LOCAL);
        model.save(row);
        return row.get(HostModel.ID);
    }

    private static Identifier key(int serverId) {
        return Identifier.of("hohenheim", String.valueOf(serverId));
    }
}
