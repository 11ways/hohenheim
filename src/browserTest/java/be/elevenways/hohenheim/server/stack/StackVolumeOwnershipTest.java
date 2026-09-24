package be.elevenways.hohenheim.server.stack;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stack purge removes only volumes the ONE ownership test attributes to it: the owner
 * pair AND this controller's token, so another controller's stack #N on a shared daemon is
 * never swept up with ours.
 */
class StackVolumeOwnershipTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
    }

    @Test
    void aVolumeBelongsToAStackOnlyWhenThisControllerLabelledIt() {
        Db.run(datasource, () -> {
            // 1. Our own stack owner labels attribute the volume to the stack.
            Map<String, String> ours = OwnerLabels.of(StackModel.MODEL_ID, 7);
            assertThat(StackVolumes.ownedByStack(ours, 7, "shop"))
                .as("step 1: our stack #7 owns its volume").isTrue();

            // 2. The same owner pair written by ANOTHER controller is not ours: before the
            //    shared test the controller token was left out and this answered true.
            Map<String, String> foreign = new HashMap<>(ours);
            foreign.put(OwnerLabels.CONTROLLER, "another-controller");
            assertThat(StackVolumes.ownedByStack(foreign, 7, "shop"))
                .as("step 2: another controller's stack #7 is not ours to purge").isFalse();

            // 3. A different stack id is not this stack.
            assertThat(StackVolumes.ownedByStack(ours, 8, "other"))
                .as("step 3: stack #8 does not own stack #7's volume").isFalse();

            // 4. A lowered-instance owner of another controller never reaches the record
            //    lookup: foreign instance ids name OUR rows only by coincidence.
            Map<String, String> foreignInstance = new HashMap<>(OwnerLabels.of(InstanceModel.MODEL_ID, 1));
            foreignInstance.put(OwnerLabels.CONTROLLER, "another-controller");
            assertThat(StackVolumes.ownedByStack(foreignInstance, 7, "shop"))
                .as("step 4: another controller's instance volume is not ours").isFalse();

            // 5. An unlabelled volume belongs to nobody.
            assertThat(StackVolumes.ownedByStack(Map.of(), 7, "shop"))
                .as("step 5: no owner labels, no owner").isFalse();
        });
    }
}
