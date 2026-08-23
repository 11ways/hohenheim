package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.cms.common.action.ActionContext;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The APPLICATION half of "a git push never starts a workload an operator stopped".
 *
 * AIDEV-NOTE: the defect this pins. The policy shipped for workspaces only, on the branch
 * of {@code InstanceService.deploy} that reaches {@code WorkspaceBuilds}; the OTHER branch
 * -- a release-managed application -- took the trigger as a word, used it for the activity
 * row and nothing else, and called {@code ApplicationReleases.converge} unconditionally. A
 * push to a stopped application therefore built an image, minted a release and started it,
 * which is exactly what the workspace lane was fixed for. The daemon here is a fake; the
 * deploy verbs are the production ones.
 */
class ApplicationPushPolicyTest {

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;

    /** The panel's own action builders are protected; the panel is a subject here. */
    private static final class ExposedInstanceResource extends InstanceResource {
        @Override public RowAction<Row> deployAction() {
            return super.deployAction();
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        daemon = new FakeDockerDaemon();
        daemon.install();
        daemon.installContainerKind();
        savedProbeTimeout = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = HohenheimSettings.VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 2);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll
    static void tearDown() {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
    }

    /**
     * A push must not resurrect an application an operator stopped -- and must still say,
     * on the record, that it arrived and what was decided about it.
     */
    @Test
    void aWebhookDeployOfAStoppedApplicationRecordsItselfAndStartsNothing() {
        Db.run(datasource, () -> {
            int applicationId = application("push-policy-app");
            try {
                // 1. A MANUAL deploy through the funnel brings the application up: one
                //    serving release, really running at the daemon.
                new InstanceService().deploy(applicationId, DeployTrigger.MANUAL);
                Row serving = ApplicationReleases.ownedServing(applicationId);
                assertThat(serving)
                    .as("step 1: a manual deploy generates a serving release")
                    .isNotNull();
                int servingId = serving.get(InstanceModel.ID);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(servingId)))
                    .as("step 1: and it really runs at the daemon")
                    .isTrue();

                // 2. The operator stops the application. The stop settles on the release
                //    it owns, and the STORED status is what records the intention.
                new InstanceService().stop(applicationId);
                assertThat((String) Models.get(InstanceModel.class).findById(servingId)
                        .get(InstanceModel.STATUS))
                    .as("step 2: the operator's stop wrote `stopped` on the release")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(servingId)))
                    .as("step 2: and settled at the daemon")
                    .isFalse();

                // 3. THE DEFECT: the forge webhook's own verb, on the same record. It is
                //    refused BY NAME, and nothing is started.
                int releasesBefore = ApplicationReleases.ownedInstances(applicationId).size();
                Throwable pushed = catchThrowable(() -> ApplicationDeploys.deploy(
                    applicationId, "main", DeployTrigger.WEBHOOK));
                assertThat(pushed)
                    .as("step 3: a push does not start an application someone stopped")
                    .isInstanceOf(Violations.class)
                    .hasMessageContaining("push_does_not_start_stopped_workload")
                    .as("step 3: and names the application it is talking about")
                    .hasMessageContaining("push-policy-app");
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(servingId)))
                    .as("step 3: the stopped release stays down")
                    .isFalse();
                assertThat(ApplicationReleases.ownedInstances(applicationId))
                    .as("step 3: and no release was minted, so nothing was built or spent")
                    .hasSize(releasesBefore);

                // 4. The push is nonetheless VISIBLE: the durable operation row says it
                //    arrived, that it was REFUSED rather than broken, and why.
                Row operation = lastOperation(applicationId);
                assertThat((String) operation.get(BuildOperationModel.STATUS))
                    .as("step 4: recorded as refused, not as a failure and not as a success")
                    .isEqualTo(BuildOperationModel.STATUS_REFUSED);
                assertThat((String) operation.get(BuildOperationModel.SOURCE_REF))
                    .as("step 4: naming the branch the push carried")
                    .isEqualTo("main");
                assertThat((String) operation.get(BuildOperationModel.FAILURE_REASON))
                    .as("step 4: carrying the decision in words a user can act on, not a"
                        + " violation key")
                    .contains("push-policy-app")
                    .contains("git push")
                    .doesNotContain("push_does_not_start_stopped_workload");
                assertThat((String) operation.get(BuildOperationModel.LOG))
                    .as("step 4: and the deploy log states it too")
                    .contains("not deployed:");

                // 5. FALSIFIED on the TRIGGER, with the state held constant: the identical
                //    deploy asked for by a person, against the same stopped application,
                //    starts it. An operator pressing Deploy IS the explicit intent.
                new InstanceService().deploy(applicationId, DeployTrigger.MANUAL);
                Row afterManual = ApplicationReleases.ownedServing(applicationId);
                assertThat((String) afterManual.get(InstanceModel.STATUS))
                    .as("step 5: a manual deploy of the same stopped application starts it")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(
                        afterManual.get(InstanceModel.ID))))
                    .as("step 5: with a release really running at the daemon")
                    .isTrue();

                // 6. FALSIFIED on the STATE, with the trigger held constant: the webhook
                //    lane is untouched for an application that is running -- the ordinary
                //    push, which is the whole point of auto-deploy.
                ApplicationDeploys.deploy(applicationId, "main", DeployTrigger.WEBHOOK);
                assertThat((String) ApplicationReleases.ownedServing(applicationId)
                        .get(InstanceModel.STATUS))
                    .as("step 6: a push to a RUNNING application deploys exactly as before")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
                assertThat((String) lastOperation(applicationId)
                        .get(BuildOperationModel.STATUS))
                    .as("step 6: and records no second refusal")
                    .isEqualTo(BuildOperationModel.STATUS_REFUSED);
                assertThat(operationsOf(applicationId))
                    .as("step 6: the refusal is the ONLY build operation this journey"
                        + " wrote -- an image-sourced deploy builds nothing")
                    .hasSize(1);
            } finally {
                ApplicationReleases.destroyFor(applicationId);
            }
        });
    }

    /**
     * An operator pressing Deploy is recorded as an operator, not as the control plane.
     *
     * AIDEV-NOTE: the row action passed NO reason, so every panel deploy landed on the
     * funnel's default word and was answerable only as "system" -- while MANUAL, the
     * member that describes exactly what happened, sat unused.
     */
    @Test
    void theDeployRowActionRecordsTheOperatorWhoPressedIt() {
        Db.run(datasource, () -> {
            assertThat(ActivityLog.isInstalled())
                .as("the activity log must be installed or this test proves nothing")
                .isTrue();
            int applicationId = application("row-action-app");
            try {
                // 1. The panel's OWN row action, invoked directly: no HTTP, no markup.
                ExposedInstanceResource panel = new ExposedInstanceResource();
                RowAction.Invoke<Row> deploy = (RowAction.Invoke<Row>) panel.deployAction();
                Row row = Models.get(InstanceModel.class).findById(applicationId);
                Accountability.runAs(operator("42"), () -> deploy.handler().apply(row,
                    ActionContext.of(AccessContext.anonymous())));

                // 2. The deploy is recorded as MANUAL -- the trigger that describes a
                //    person standing there asking, and the one whose permission to start
                //    a stopped workload is a decision rather than a default.
                List<Row> deployed = activityFor(applicationId,
                    InstanceService.ACTIVITY_DEPLOY_ACTION);
                assertThat(deployed)
                    .as("step 2: the row action recorded the deploy")
                    .isNotEmpty();
                assertThat((String) deployed.get(0).get(ActivityModel.DETAIL))
                    .as("step 2: as MANUAL, never as the control plane's own default")
                    .isEqualTo(DeployTrigger.MANUAL.word())
                    .isNotEqualTo(DeployTrigger.SYSTEM.word());
                assertThat((String) deployed.get(0).get(ActivityModel.ACTOR))
                    .as("step 2: attributed to the operator who clicked")
                    .isEqualTo("42");
            } finally {
                ApplicationReleases.destroyFor(applicationId);
            }
        });
    }

    /**
     * The WORKLOAD funnel enforces the same policy, for kinds that own their container.
     *
     * AIDEV-NOTE: this is the preview lane's refusal. A preview REUSES its instance row
     * across refreshes ({@code PreviewDeployments.converge} only mints one when the row is
     * gone), so a push to the branch of a preview an operator stopped reaches
     * {@code InstanceService.deploy} on the WEBHOOK trigger with a record whose stored
     * status is `stopped`. The subject here is that funnel and a plain container record,
     * because the preview's own lane insists on a real checkout and a sandbox build before
     * it ever gets there -- {@code PreviewDeploymentLiveTest} is where that half runs.
     */
    @Test
    void aWebhookNeverStartsAStoppedWorkloadOnTheWorkloadFunnelEither() {
        Db.run(datasource, () -> {
            int instanceId = container("push-policy-container");
            InstanceService service = new InstanceService();
            try {
                // 1. Up by hand, then stopped by the operator: the ordinary starting point.
                service.deploy(instanceId, DeployTrigger.MANUAL);
                service.stop(instanceId);
                assertThat(statusOf(instanceId))
                    .as("step 1: the operator's stop wrote `stopped` on the record")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);

                // 2. A push-triggered deploy of that record is refused by name.
                Throwable pushed = catchThrowable(
                    () -> service.deploy(instanceId, DeployTrigger.WEBHOOK));
                assertThat(pushed)
                    .as("step 2: the workload funnel refuses it for this kind too")
                    .isInstanceOf(Violations.class)
                    .hasMessageContaining("push_does_not_start_stopped_workload");
                assertThat(statusOf(instanceId))
                    .as("step 2: and the record was never stamped as running")
                    .isEqualTo(InstanceModel.STATUS_STOPPED);

                // 3. FALSIFIED on the trigger: the same call from a person starts it, so
                //    this is a decision about WHO asked and not a broken deploy path.
                service.deploy(instanceId, DeployTrigger.MANUAL);
                assertThat(statusOf(instanceId))
                    .as("step 3: a manual deploy of the same record starts it")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);

                // 4. FALSIFIED on the state: a push to a RUNNING record still deploys.
                service.deploy(instanceId, DeployTrigger.WEBHOOK);
                assertThat(statusOf(instanceId))
                    .as("step 4: and a push to a running record is untouched")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
            } finally {
                service.destroy(instanceId);
            }
        });
    }

    // -- fixtures --------------------------------------------------------------

    /** A plain container record on the local fake daemon. */
    private static int container(String name) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", "v1");
        settings.put("container_port", 8080);
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, name);
        instance.set(InstanceModel.KIND, DockerContainerKind.ID.toString());
        instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instance.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(instance);
        return instance.get(InstanceModel.ID);
    }

    private static String statusOf(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId)
            .get(InstanceModel.STATUS);
    }

    /** An authored application with an image source the fake daemon answers for. */
    private static int application(String name) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", "v1");
        settings.put("container_port", 8080);
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        application.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(application);
        return application.get(InstanceModel.ID);
    }

    private static List<Row> operationsOf(int applicationId) {
        return Models.get(BuildOperationModel.class).find()
            .where(BuildOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(BuildOperationModel.FOR_ID.eq(applicationId))
            .orderBy(BuildOperationModel.ID, SortOrder.DESC)
            .all();
    }

    private static Row lastOperation(int applicationId) {
        List<Row> rows = operationsOf(applicationId);
        assertThat(rows).as("the deploy recorded a build operation").isNotEmpty();
        return rows.get(0);
    }

    private static List<Row> activityFor(int instanceId, String action) {
        return Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(instanceId)))
            .where(ActivityModel.ACTION.eq(action))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .all();
    }

    private static Accountability operator(String id) {
        return new Accountability(id, "Operator " + id, "10.0.0.1", "junit",
            Accountability.ORIGIN_WEB);
    }
}
