package be.elevenways.hohenheim.test.build;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.build.BuildQuota;
import be.elevenways.hohenheim.server.build.BuildRequest;
import be.elevenways.hohenheim.server.build.SandboxedBuilds;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A build's ending stamp, written once the claim of the record it builds for is let go, names who ran the build:
 * an operator's build stays that operator's, and a build system work starts names the system under its purpose.
 *
 * AIDEV-NOTE: daemon-free on purpose. The sandbox seam fails the build right after its durable running row, so the
 * FAILED stamp travels the same afterRelease write a real ending does, and no docker, network policy or image is
 * touched (the fakes below throw if they are).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class BuildStampAttributionTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    /** The instance the builds run for; no row is needed, the claim and the history key on the id. */
    private static final int OWNER_ID = 977_301;

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
    }

    @Test
    void aBuildsEndingStampNamesWhoRanTheBuildJourney() {
        Db.run(datasource, () -> {
            assertThat(HistoryTestSupport.recordsOptional())
                .as("optional history must be recording or this test proves nothing")
                .isTrue();

            // 1. An operator's build that fails past its running row: the FAILED stamp is that operator's.
            UUID operatorId = Uuids.v7();
            Accountability.runAs(RecordedChanges.operator(operatorId), this::failingBuild);
            assertThat(endingOfLatestBuild().actedBy())
                .as("step 1: the ending stamp names the operator who ran the build")
                .isEqualTo(RecordedChanges.operatorWork(operatorId));

            // 2. The same build started by system work (a git poll): the stamp is the system's, under that purpose.
            ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.GIT_POLL, this::failingBuild);
            assertThat(endingOfLatestBuild().attribution())
                .as("step 2: a system-run build's ending stamp is the system's work under its purpose")
                .isEqualTo(RecordedChanges.systemWork(HohenheimSystemPurposes.GIT_POLL));
        });
    }

    /** Run one build whose sandbox seam fails it after the running row; the failure is the build's own, rethrown. */
    private void failingBuild() {
        SandboxedBuilds builds = new SandboxedBuilds(new DockerClient(new FakeDockerDaemon()),
            new WorkloadNetworkPolicy((args, stdin) -> {
                throw new AssertionError("nft must never run for a build that ends before its sandbox");
            }, () -> true),
            () -> {
                throw new IllegalStateException("sandbox refused by the test");
            });
        Path context;
        try {
            context = Files.createTempDirectory("hohenheim-build-attribution");
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        Throwable thrown = catchThrowable(() -> builds.run(new BuildRequest(InstanceModel.MODEL_ID, OWNER_ID,
            BuildOperationModel.KIND_DOCKERFILE, context, "Dockerfile", "hohenheim-attribution:latest", Map.of(),
            "test-ref", null, new BuildQuota(1.0, 512, 1024L * 1024 * 1024, 60_000, 64, 64 * 1024,
                256L * 1024 * 1024))));
        assertThat(thrown).as("the seam's failure reaches the caller").hasMessage("sandbox refused by the test");
    }

    /** The newest build of the owner, which must have settled as FAILED, and its recorded ending. */
    private static RecordedChanges.Recorded endingOfLatestBuild() {
        BuildOperationModel model = Models.get(BuildOperationModel.class);
        var build = model.find().where(BuildOperationModel.FOR_ID.eq(OWNER_ID))
            .orderBy(BuildOperationModel.ID, SortOrder.DESC).first();
        assertThat((String) build.get(BuildOperationModel.STATUS))
            .as("the build settled as failed")
            .isEqualTo(BuildOperationModel.LIFECYCLE.stored(OperationStatus.FAILED));
        return RecordedChanges.lastUpdateOf(model, build.get(BuildOperationModel.ID),
            BuildOperationModel.STATUS.getName());
    }
}
