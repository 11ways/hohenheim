package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.runtime.DockerInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.runtime.WorkloadAttribution.WorkloadClaim;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Docker driver's {@code claimOf} contract, over the in-memory daemon: attribution
 * is decided from the FULL owner-label triple (controller, model, id). A same-named
 * container of another RECORD is FOREIGN, and so is one of the SAME record id minted by
 * another CONTROLLER -- record ids are allocated per database, so two controllers on one
 * daemon label their respective record #1 identically (the OwnerLabels lesson); a
 * model+id match alone would let a migration pre-flight delete a stranger's workload as
 * "debris".
 */
class DockerWorkloadAttributionTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        // The controller identity behind OwnerLabels.of resolves through the CURRENT
        // datasource; one registered database per class, like every daemon-fake test.
        HohenheimTestRuntime.ensureBooted();
    }

    private static InstanceSpec spec(String handle, Map<String, String> ownerLabels) {
        return new InstanceSpec(handle, "fake/image", null, Map.of(), Map.of(),
            (PortPublication) null, ResourceLimits.none(),
            new ContainerHardening.Profile("fake", List.of()), ownerLabels);
    }

    private static WorkloadClaim claim(DockerInstanceRuntime runtime, InstanceSpec spec) {
        try {
            return runtime.claimOf(spec);
        } catch (IOException unreachable) {
            throw new AssertionError("the fake daemon must always answer", unreachable);
        }
    }

    @Test
    void attributionDemandsTheFullOwnerTripleNeverModelAndIdAlone() throws Exception {
        Db.run(datasource, () -> {
            FakeDockerDaemon daemon = new FakeDockerDaemon();
            // claimOf only inspects; any kernel-policy touch would be a contract breach.
            DockerInstanceRuntime runtime = new DockerInstanceRuntime(
                new DockerClient(daemon),
                new WorkloadNetworkPolicy((args, stdin) -> {
                    throw new AssertionError("nft must never run for attribution");
                }, () -> true));
            InstanceSpec ours = spec("attr-ours", OwnerLabels.of(InstanceModel.MODEL_ID, 41777));

            // 1. Nothing exists under the handle: ABSENT, never a guess.
            assertThat(claim(runtime, ours))
                .as("step 1: an absent workload answers ABSENT")
                .isEqualTo(WorkloadClaim.ABSENT);

            // 2. Our own container (full triple stamped at create) answers OURS.
            try {
                daemon.runtime().create(ours);
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
            assertThat(claim(runtime, ours))
                .as("step 2: our own container answers OURS")
                .isEqualTo(WorkloadClaim.OURS);

            // 3. A same-named container of ANOTHER RECORD is FOREIGN: a name collision,
            //    never a replace target.
            try {
                daemon.runtime().create(
                    spec("attr-stranger", OwnerLabels.of(InstanceModel.MODEL_ID, 999999999)));
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
            assertThat(claim(runtime,
                    spec("attr-stranger", OwnerLabels.of(InstanceModel.MODEL_ID, 41777))))
                .as("step 3: another record's container is FOREIGN")
                .isEqualTo(WorkloadClaim.FOREIGN);

            // 4. THE two-controllers hazard: SAME model and id, minted by a DIFFERENT
            //    controller. model+id genuinely match, so only the controller label can
            //    refuse -- an OURS here lets a migration delete a rival's workload.
            Map<String, String> alien = Map.of(
                OwnerLabels.MODEL, InstanceModel.MODEL_ID.toString(),
                OwnerLabels.ID, "41777",
                OwnerLabels.CONTROLLER, "some-other-controller-token");
            try {
                daemon.runtime().create(spec("attr-alien", alien));
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
            assertThat(claim(runtime,
                    spec("attr-alien", OwnerLabels.of(InstanceModel.MODEL_ID, 41777))))
                .as("step 4: the same record id of ANOTHER controller is FOREIGN --"
                    + " ids are per-database, the controller token is what disambiguates")
                .isEqualTo(WorkloadClaim.FOREIGN);
        });
    }
}
