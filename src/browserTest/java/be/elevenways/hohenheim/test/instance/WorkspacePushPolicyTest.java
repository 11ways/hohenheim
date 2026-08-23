package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.hohenheim.server.ControllerScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * WHO asked decides whether a deploy may start a workload nobody currently wants running.
 *
 * AIDEV-NOTE: the defect. {@code InstanceService.deploy} ends by bringing the workload up
 * ({@code WorkspaceBuilds.ensureRunning}), which is right for a person pressing Deploy and
 * wrong for a forge webhook: an operator who stopped a workspace decided something about
 * their host's resources, and a stranger's {@code git push} undoing that spends someone
 * else's machine. The whole journey below runs the PRODUCTION deploy verb -- only the
 * daemon behind it is fake.
 */
class WorkspacePushPolicyTest {

    private static SqlDatasource datasource;
    private static int hostId;
    private static FakeWorkspaceDaemon daemon;
    private static Integer savedUidBase;

    @BeforeAll
    static void setUp() throws Exception {
        BackupLaneFixture fixture = BackupLaneFixture.install();
        datasource = fixture.datasource;
        hostId = fixture.hostId;
        savedUidBase = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Storage.VOLUME_UID_BASE);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE, 200000);
        daemon = FakeWorkspaceDaemon.install();
    }

    @AfterAll
    static void tearDown() {
        FakeWorkspaceDaemon.uninstall();
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE,
            savedUidBase);
        BackupLaneFixture.uninstall();
    }

    /**
     * A push must not resurrect a workspace an operator stopped -- and must still say, on
     * the record, that it arrived and what was decided about it.
     */
    @Test
    void aWebhookDeployOfAStoppedWorkspaceRecordsItselfAndStartsNothing() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            int id = workspace("push-policy");
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);

            // 1. A MANUAL deploy of a workspace that has never run brings it up and
            //    deploys its source -- the behaviour the trigger policy must not break.
            WorkspaceBuilds.Outcome manual =
                new WorkspaceBuilds(service).deploy(id, null, DeployTrigger.MANUAL.word());
            assertThat(manual.commitSha())
                .as("step 1: a manual deploy checks the source out")
                .isEqualTo(FakeWorkspaceDaemon.COMMIT);
            assertThat(daemon.isRunning(handle))
                .as("step 1: and leaves the workspace running")
                .isTrue();

            // 2. The operator stops it. That is an intention about their own host.
            service.stop(id);
            assertThat(daemon.isRunning(handle))
                .as("step 2: the operator's stop settles at the daemon")
                .isFalse();

            // 3. THE DEFECT: someone else pushes. The deploy is refused BY NAME, and the
            //    refusal says what was decided rather than blaming a failure.
            Throwable pushed = catchThrowable(() -> new WorkspaceBuilds(service)
                .deploy(id, null, DeployTrigger.WEBHOOK.word()));
            assertThat(pushed)
                .as("step 3: a push does not start a workspace someone stopped")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("workspace_push_does_not_start")
                .as("step 3: and names the workspace it is talking about")
                .hasMessageContaining("push-policy");
            assertThat(daemon.isRunning(handle))
                .as("step 3: nothing was started, so nothing was spent")
                .isFalse();

            // 4. The push is nonetheless VISIBLE: the durable operation row says it
            //    arrived, that it was REFUSED rather than broken, and why.
            Row operation = lastOperation(id);
            assertThat((String) operation.get(BuildOperationModel.STATUS))
                .as("step 4: recorded as refused, not as a failure and not as a success")
                .isEqualTo(BuildOperationModel.STATUS_REFUSED);
            assertThat((String) operation.get(BuildOperationModel.FAILURE_REASON))
                .as("step 4: carrying the decision in words a user can act on, not a"
                    + " violation key")
                .contains("push-policy")
                .contains("git push")
                .doesNotContain("workspace_push_does_not_start");
            assertThat((String) operation.get(BuildOperationModel.LOG))
                .as("step 4: and the deploy log states it too")
                .contains("not deployed:");

            // 5. FALSIFIED on the TRIGGER, with the state held constant: the identical
            //    call from a person, against the same stopped workspace, starts it.
            WorkspaceBuilds.Outcome byHand =
                new WorkspaceBuilds(service).deploy(id, null, DeployTrigger.MANUAL.word());
            assertThat(byHand.status().running())
                .as("step 5: a manual deploy of the same stopped workspace starts it")
                .isTrue();
            assertThat((String) lastOperation(id).get(BuildOperationModel.STATUS))
                .as("step 5: and records an ordinary success")
                .isEqualTo(BuildOperationModel.STATUS_SUCCEEDED);

            // 6. FALSIFIED on the STATE, with the trigger held constant: the webhook lane
            //    is untouched for a workspace that is running -- the ordinary push.
            WorkspaceBuilds.Outcome push = new WorkspaceBuilds(service)
                .deploy(id, null, DeployTrigger.WEBHOOK.word());
            assertThat(push.commitSha())
                .as("step 6: a push to a RUNNING workspace deploys exactly as before")
                .isEqualTo(FakeWorkspaceDaemon.COMMIT);
            assertThat((String) lastOperation(id).get(BuildOperationModel.STATUS))
                .as("step 6: and succeeds")
                .isEqualTo(BuildOperationModel.STATUS_SUCCEEDED);
        });
    }

    /**
     * "The daemon says it is down" and "I could not ask the daemon" are different answers,
     * and only the first is an operator's intention.
     */
    @Test
    void anUnreachableHostIsNeverReadAsAnOperatorsStop() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            int id = workspace("push-unreachable");

            // 1. Bring it up by hand first, so the record is an ordinary running one.
            new WorkspaceBuilds(service).deploy(id, null, DeployTrigger.MANUAL.word());

            // 2. The host becomes unaddressable. A push arrives.
            daemon.setUnreachable(true);
            Throwable pushed = catchThrowable(() -> new WorkspaceBuilds(service)
                .deploy(id, null, DeployTrigger.WEBHOOK.word()));

            // 3. It must NOT be told "you stopped this workspace": an unreachable daemon
            //    is not evidence that anybody stopped anything.
            assertThat(pushed)
                .as("step 3: the push fails, because nothing could be done")
                .isInstanceOf(Violations.class);
            assertThat(String.valueOf(pushed.getMessage()))
                .as("step 3: but never with the trigger policy's refusal")
                .doesNotContain("workspace_push_does_not_start");

            // 4. And it is recorded as a FAILURE, which is what it is -- the refused
            //    status is reserved for a decision, never for a broken host.
            assertThat((String) lastOperation(id).get(BuildOperationModel.STATUS))
                .as("step 4: an unreachable host records a failure, not a refusal")
                .isEqualTo(BuildOperationModel.STATUS_FAILED);

            daemon.setUnreachable(false);
        });
    }

    /** The trigger vocabulary reads the reason word every surface already passes. */
    @Test
    void theTriggerVocabularyReadsTheReasonWordTheCallersAlreadyPass() {
        // 1. The two words the shipped surfaces pass today, and what each decides.
        assertThat(DeployTrigger.of("manual"))
            .as("step 1: the row action's word")
            .isEqualTo(DeployTrigger.MANUAL);
        assertThat(DeployTrigger.of("webhook"))
            .as("step 1: and the forge webhook's")
            .isEqualTo(DeployTrigger.WEBHOOK);
        assertThat(DeployTrigger.WEBHOOK.startsStoppedWorkload())
            .as("step 1: only the webhook is barred from starting a stopped workload")
            .isFalse();

        // 2. The funnel's own default reason resolves, so a deploy that names no trigger
        //    is control-plane convergence and keeps starting what it converges.
        assertThat(DeployTrigger.of(InstanceService.DEFAULT_DEPLOY_REASON))
            .as("step 2: the default reason is the system trigger")
            .isEqualTo(DeployTrigger.SYSTEM);
        assertThat(DeployTrigger.SYSTEM.startsStoppedWorkload())
            .as("step 2: which may start what it is converging")
            .isTrue();

        // 3. An unrecognised word answers like SYSTEM, by declared decision: every
        //    in-house lane is convergence, and the third-party lane is the one that has
        //    to be built (and declared).
        assertThat(DeployTrigger.of("adoption"))
            .as("step 3: an undeclared word reads as control-plane convergence")
            .isEqualTo(DeployTrigger.SYSTEM);
        assertThat(DeployTrigger.of(null))
            .as("step 3: and so does no word at all")
            .isEqualTo(DeployTrigger.SYSTEM);
    }

    private static Row lastOperation(int instanceId) {
        List<Row> rows = Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(instanceId))
            .orderBy(BuildOperationModel.ID, SortOrder.DESC)
            .all();
        assertThat(rows).as("the deploy recorded a build operation").isNotEmpty();
        return rows.get(0);
    }

    /** A source-declaring workspace on the fixture host, with no port to publish. */
    private static int workspace(String name) {
        RuntimeImageModel images = Models.get(RuntimeImageModel.class);
        Row image = images.findByName("push-policy-image");
        if (image == null) {
            image = images.createEmptyRow();
            image.set(RuntimeImageModel.NAME, "push-policy-image");
            image.set(RuntimeImageModel.INCUS_IMAGE, "hohenheim/node-22");
            image.set(RuntimeImageModel.DOCKER_IMAGE, "hohenheim/node-22:1");
            image.set(RuntimeImageModel.DEFAULT_COMMAND, "npm start");
            image.set(RuntimeImageModel.BUILD_CONTEXT, "images/node-22");
            image.set(RuntimeImageModel.ENABLED, true);
            images.save(image);
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("repository_url", "https://git.example.test/team/app.git");
        settings.put("branch", "main");
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, WorkspaceKind.ID.toString());
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, hostId);
        row.set(InstanceModel.RUNTIME_IMAGE_ID, image.get(RuntimeImageModel.ID));
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
