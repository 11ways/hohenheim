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
                new WorkspaceBuilds(service).deploy(id, null, DeployTrigger.MANUAL);
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
                .deploy(id, null, DeployTrigger.WEBHOOK));
            assertThat(pushed)
                .as("step 3: a push does not start a workspace someone stopped")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("push_does_not_start_stopped_workload")
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
                .doesNotContain("push_does_not_start_stopped_workload");
            assertThat((String) operation.get(BuildOperationModel.LOG))
                .as("step 4: and the deploy log states it too")
                .contains("not deployed:");

            // 5. FALSIFIED on the TRIGGER, with the state held constant: the identical
            //    call from a person, against the same stopped workspace, starts it.
            WorkspaceBuilds.Outcome byHand =
                new WorkspaceBuilds(service).deploy(id, null, DeployTrigger.MANUAL);
            assertThat(byHand.status().running())
                .as("step 5: a manual deploy of the same stopped workspace starts it")
                .isTrue();
            assertThat((String) lastOperation(id).get(BuildOperationModel.STATUS))
                .as("step 5: and records an ordinary success")
                .isEqualTo(BuildOperationModel.STATUS_SUCCEEDED);

            // 6. FALSIFIED on the STATE, with the trigger held constant: the webhook lane
            //    is untouched for a workspace that is running -- the ordinary push.
            WorkspaceBuilds.Outcome push = new WorkspaceBuilds(service)
                .deploy(id, null, DeployTrigger.WEBHOOK);
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
            new WorkspaceBuilds(service).deploy(id, null, DeployTrigger.MANUAL);

            // 2. The host becomes unaddressable. A push arrives.
            daemon.setUnreachable(true);
            Throwable pushed = catchThrowable(() -> new WorkspaceBuilds(service)
                .deploy(id, null, DeployTrigger.WEBHOOK));

            // 3. It must NOT be told "you stopped this workspace": an unreachable daemon
            //    is not evidence that anybody stopped anything.
            assertThat(pushed)
                .as("step 3: the push fails, because nothing could be done")
                .isInstanceOf(Violations.class);
            assertThat(String.valueOf(pushed.getMessage()))
                .as("step 3: but never with the trigger policy's refusal")
                .doesNotContain("push_does_not_start_stopped_workload");

            // 4. And it is recorded as a FAILURE, which is what it is -- the refused
            //    status is reserved for a decision, never for a broken host.
            assertThat((String) lastOperation(id).get(BuildOperationModel.STATUS))
                .as("step 4: an unreachable host records a failure, not a refusal")
                .isEqualTo(BuildOperationModel.STATUS_FAILED);

            daemon.setUnreachable(false);
        });
    }

    /**
     * The stored reason word reads back onto its member, and anything else fails CLOSED.
     *
     * AIDEV-NOTE: {@code of} used to be the vocabulary's ENTRY point -- every surface
     * passed a free string and this method classified it, answering SYSTEM (the permissive
     * member) for anything it did not recognise. The surfaces now pass MEMBERS, so this is
     * the storage-boundary parser and nothing else; what it reads is a word that was
     * WRITTEN by {@link DeployTrigger#word()}, and a word that was not is unattributable
     * rather than trusted.
     */
    @Test
    void theStoredReasonWordReadsBackAndAnUnknownOneFailsClosed() {
        // 1. The words the shipped surfaces record, and what each decides.
        assertThat(DeployTrigger.of("manual"))
            .as("step 1: the row action's word")
            .isEqualTo(DeployTrigger.MANUAL);
        assertThat(DeployTrigger.of("webhook"))
            .as("step 1: and the forge webhook's")
            .isEqualTo(DeployTrigger.WEBHOOK);
        assertThat(DeployTrigger.of("api"))
            .as("step 1: and the automation API's, which stays its own word")
            .isEqualTo(DeployTrigger.API);
        assertThat(DeployTrigger.WEBHOOK.startsStoppedWorkload())
            .as("step 1: only the webhook is barred from starting a stopped workload")
            .isFalse();

        // 2. The funnel's own default reason resolves, so a deploy that names no trigger
        //    is control-plane convergence and keeps starting what it converges. The stack
        //    adoption lane is one of those: it reaches the funnel with no word at all.
        assertThat(DeployTrigger.of(InstanceService.DEFAULT_DEPLOY_REASON))
            .as("step 2: the default reason is the system trigger")
            .isEqualTo(DeployTrigger.SYSTEM);
        assertThat(DeployTrigger.SYSTEM.startsStoppedWorkload())
            .as("step 2: which may start what it is converging")
            .isTrue();
        assertThat(DeployTrigger.MANUAL.startsStoppedWorkload()
                && DeployTrigger.API.startsStoppedWorkload())
            .as("step 2: and so may a person and an API key holder who asked")
            .isTrue();

        // 3. THE FAIL-CLOSED HALF: an unrecognised word buys NO permission. It used to buy
        //    the permissive classification, so a typo, a renamed member or a new surface
        //    spelling its own word silently earned the right to start a stopped workload.
        assertThat(DeployTrigger.of("adoption").startsStoppedWorkload())
            .as("step 3: an undeclared word may not start a stopped workload")
            .isFalse();
        assertThat(DeployTrigger.of(null).startsStoppedWorkload())
            .as("step 3: and neither may no word at all")
            .isFalse();
        assertThat(DeployTrigger.of("  WEBHOOK  "))
            .as("step 3: while a known word is still read through whitespace and case")
            .isEqualTo(DeployTrigger.WEBHOOK);
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
