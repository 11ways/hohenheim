package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.WorkloadAttribution;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE ownership test ({@link OwnerLabels#matches}) includes the controller token, and the
 * guards that used to leave it out now ask it.
 *
 * AIDEV-NOTE: record ids are allocated per DATABASE, so two controllers on one daemon both
 * own a "record #1". A guard that compares model+id alone attributes the other controller's
 * workload to this record -- the Incus claim did exactly that until this wave. Step 4 is
 * that collision staged against the real driver.
 */
class OwnershipControllerTokenTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void ownershipIsModelIdAndControllerAndTheIncusClaimAsksAllThree() {
        Db.run(datasource, () -> {
            String token = ControllerIdentity.token();
            OwnerLabels.Owner ours = OwnerLabels.parse(OwnerLabels.of(InstanceModel.MODEL_ID, 7));

            // 1. The labels this controller stamps match its own record.
            assertThat(OwnerLabels.matches(ours, InstanceModel.MODEL_ID, 7))
                .as("step 1: our own labels match our record").isTrue();
            assertThat(OwnerLabels.matches(ours, ours))
                .as("step 1: and match the owner they name").isTrue();

            // 2. The SAME model and id stamped by another controller is not ours.
            Map<String, String> theirs = new LinkedHashMap<>(OwnerLabels.of(InstanceModel.MODEL_ID, 7));
            theirs.put(OwnerLabels.CONTROLLER, token + "x");
            assertThat(OwnerLabels.matches(OwnerLabels.parse(theirs), InstanceModel.MODEL_ID, 7))
                .as("step 2: another controller's record #7 is not ours").isFalse();

            // 3. A pre-namespace resource (no controller label), another record, and a
            //    record-less caller all match nothing.
            Map<String, String> legacy = new LinkedHashMap<>(OwnerLabels.of(InstanceModel.MODEL_ID, 7));
            legacy.remove(OwnerLabels.CONTROLLER);
            assertThat(OwnerLabels.matches(OwnerLabels.parse(legacy), InstanceModel.MODEL_ID, 7))
                .as("step 3: an unattributable pre-namespace resource is not ours").isFalse();
            assertThat(OwnerLabels.matches(ours, InstanceModel.MODEL_ID, 8))
                .as("step 3: another record of ours is not this record").isFalse();
            assertThat(OwnerLabels.matches(ours, InstanceModel.MODEL_ID, null))
                .as("step 3: a record-less caller can attribute nothing").isFalse();
            assertThat(OwnerLabels.matches(null, ours))
                .as("step 3: an unlabelled resource is nobody's").isFalse();

            // 4. THE COLLISION against the real Incus driver: an instance under our handle
            //    whose user.* labels name the same model and id of ANOTHER controller. The
            //    claim used to answer OURS (model+id only), which is how a converge would
            //    have adopted -- and rewritten -- a stranger's workload.
            FakeIncusTransport daemon = new FakeIncusTransport();
            String handle = "hohenheim-" + token + "-instance-7";
            Map<String, Object> config = new LinkedHashMap<>();
            theirs.forEach((key, value) -> config.put("user." + key, value));
            Map<String, Object> foreign = new LinkedHashMap<>();
            foreign.put("name", handle);
            foreign.put("type", "container");
            foreign.put("config", config);
            daemon.instances.put(handle, foreign);
            IncusInstanceRuntime runtime = new IncusInstanceRuntime(new IncusClient(daemon));
            InstanceSpec spec = InstanceSpec.builder(handle, "images:debian/12",
                ResourceLimits.none(), ContainerHardening.STRICT,
                OwnerLabels.of(InstanceModel.MODEL_ID, 7)).build();
            try {
                assertThat(runtime.claimOf(spec))
                    .as("step 4: another controller's same-numbered instance is FOREIGN")
                    .isEqualTo(WorkloadAttribution.WorkloadClaim.FOREIGN);

                // 5. Once the labels are really ours, the same instance is OURS.
                OwnerLabels.of(InstanceModel.MODEL_ID, 7)
                    .forEach((key, value) -> config.put("user." + key, value));
                assertThat(runtime.claimOf(spec))
                    .as("step 5: our own labels make the claim OURS")
                    .isEqualTo(WorkloadAttribution.WorkloadClaim.OURS);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }
}
