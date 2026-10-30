package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.zenit.test.support.TestAccessContexts;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.panel.OperationWrites;
import be.elevenways.hohenheim.test.PanelEntryViews;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.server.cms.InstanceAttention;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.PlacedActionClicks;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.zenit.common.orm.activity.ActivityAction;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.history.ChangeOp;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Power accountability as a daemon-free journey: the SAME operation must be answerable
 * whichever surface performed it.
 *
 * AIDEV-NOTE: this exists because it was not true. Until 2026-08-08 only the automation
 * API recorded instance power actions; the admin row action and the delegated /manage
 * projection called the service and returned a toast, writing nothing. The status stamp
 * is a fenced updateAll, which fires no model hooks, so nothing else covered it either.
 * An audit trail with a hole in it is worse than none: it reads as complete. The row
 * action handlers are invoked here DIRECTLY (not through HTTP) so this test fails on the
 * mechanism regressing, not on a page's markup.
 */
class InstancePowerAuditTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    private static SqlDatasource datasource;
    private static int hostId;
    private static final UUID RESTART_OPERATOR = Uuids.v7();
    private static final UUID SERVICE_OPERATOR = Uuids.v7();
    /** The panel's row actions run as the all-allowed test caller, so its account is who clicked. */
    private static final UUID PANEL_OPERATOR = TestAccessContexts.TEST_ACCOUNT;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
        // ONE registered database per class: the controller identity every daemon
        // resource name resolves through reads the CURRENT datasource.
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = HostFixtures.admittedIncusHost("audit-host"));
    }

    private static int instanceRecord(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** @return the instance's recorded changes carrying the fact, oldest first */
    private static List<RecordedChanges.Recorded> activityFor(int instanceId, ActivityAction kind) {
        return RecordedChanges.withFact(Models.get(InstanceModel.class), instanceId, kind);
    }

    /**
     * @return the instance's UPDATE changes that name no act, beyond the operation state a fenced stamp records
     *         ({@link InstanceModel#RECORDED_OPERATION_STATE}): a bare field update
     */
    private static List<RecordedChanges.Recorded> bareUpdates(int instanceId) {
        Set<String> stamped = new HashSet<>();
        InstanceModel.RECORDED_OPERATION_STATE.forEach(field -> stamped.add(field.getName()));
        return RecordedChanges.of(Models.get(InstanceModel.class), instanceId, ChangeOp.UPDATE).stream()
            .filter(recorded -> recorded.facts().isEmpty() && !stamped.containsAll(recorded.delta().keySet()))
            .toList();
    }

    /**
     * {@code InstanceService.restart} driven directly: it IS stop-then-deploy, and a
     * deploy that fails after the stop landed leaves the record in the state deploy's own
     * failure policy stamps -- there is no restart-shaped compensation.
     *
     * AIDEV-NOTE: written 2026-08-13 because restart() had ZERO test callers -- the only
     * coverage was a CMS page asserting the button's URL rendered. The failure half is
     * simulated through the service's own beforeOutcomeWrite seam: the hook fires inside
     * the stop, right before its outcome write, and hands the workload to a foreign owner
     * so the following create() refuses it exactly as a taken-over container would.
     */
    @Test
    void restartIsStopThenDeployAndAFailedDeployLeavesTheRecordInError() {
        Db.run(datasource, () -> {
            // 1. A running workload: the state a restart is asked for from.
            int id = instanceRecord("audit-restart");
            InstanceService service = new InstanceService();
            Accountability.runAs(RecordedChanges.operator(RESTART_OPERATOR), () -> service.deploy(id));
            String handle = FakeNativeDaemons.handleOf(id);
            assertThat(FakeNativeDaemons.daemonOf(hostId).get(handle).running)
                .as("step 1: the workload runs before the restart").isTrue();
            assertThat((String) Models.get(InstanceModel.class).findById(id)
                    .get(InstanceModel.STATUS))
                .as("step 1: and the record says so").isEqualTo(InstanceModel.STATUS_RUNNING);

            // 2. ONE verb: restart stops and deploys again, and BOTH halves are recorded
            //    -- the composition is the service's, so the audit trail shows the pair
            //    rather than a single unexplained "restarted" row nobody can reconcile.
            Accountability.runAs(RecordedChanges.operator(RESTART_OPERATOR), () -> service.restart(id));
            assertThat(activityFor(id, HohenheimActivityAction.STOPPED))
                .as("step 2: the restart's stop half is recorded")
                .hasSize(1);
            assertThat(activityFor(id, HohenheimActivityAction.DEPLOYED))
                .as("step 2: and its deploy half, beside the initial deploy")
                .hasSize(2);
            assertThat(FakeNativeDaemons.daemonOf(hostId).get(handle).running)
                .as("step 2: the workload is running again after the restart").isTrue();
            assertThat((String) Models.get(InstanceModel.class).findById(id)
                    .get(InstanceModel.STATUS))
                .as("step 2: and the record settled RUNNING, never mid-flight")
                .isEqualTo(InstanceModel.STATUS_RUNNING);

            // 3. THE FAILURE HALF: the stop succeeds, the deploy cannot. restart() adds no
            //    compensation of its own, so the record carries deploy's own verdict.
            boolean[] stolen = {false};
            InstanceService sabotaged = new InstanceService(HostLeases.production(), () -> {
                if (stolen[0]) {
                    return;
                }
                stolen[0] = true;   // fires inside the stop, before its outcome write
                FakeNativeDaemons.daemonOf(hostId).get(handle).ownerId = "999999999";
            });
            Throwable refused = catchThrowable(
                () -> Accountability.runAs(RecordedChanges.operator(RESTART_OPERATOR),
                    () -> sabotaged.restart(id)));
            assertThat(refused)
                .as("step 3: a restart whose deploy fails refuses by name, never silently")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(violations.all())
                        .as("step 3: named as the DEPLOY failure it is")
                        .anySatisfy(violation -> assertThat(violation.message().key())
                            .isEqualTo("instance_deploy_failed")));
            assertThat((String) Models.get(InstanceModel.class).findById(id)
                    .get(InstanceModel.STATUS))
                .withFailMessage("step 3: a half-completed restart must leave the record in"
                    + " deploy's own error state -- anything else (stopped, running) reads"
                    + " as an outcome nobody produced")
                .isEqualTo(InstanceModel.STATUS_ERROR);
            assertThat(activityFor(id, HohenheimActivityAction.STOPPED))
                .as("step 3: the stop half that DID land is still recorded")
                .hasSize(2);
            assertThat(activityFor(id, HohenheimActivityAction.DEPLOYED))
                .as("step 3: and the failed deploy recorded nothing -- only settled"
                    + " operations are answerable")
                .hasSize(2);

            // 3b. The ERROR names what caused it: the failed start is stored on the record as the cause, with the
            //     failure's own message, and the dashboard item words it from there, never "exited unexpectedly". Its
            //     title says the start failed, never that sentence twice; its detail is worded and the message is
            //     the technical line under it.
            Row stopped = Models.get(InstanceModel.class).findById(id);
            assertThat(InstanceModel.stoppageCauseOf(stopped)).as("step 3b: the error's cause is stored")
                .isEqualTo(HohenheimActivityAction.WORKLOAD_START_FAILED);
            assertThat((String) stopped.get(InstanceModel.STOPPAGE_DETAIL))
                .as("step 3b: with the failure's own message").isNotBlank();
            List<AttentionItem> items = new ArrayList<>();
            InstanceAttention.crashedInstances(items);
            AttentionItem item = items.stream().filter(candidate -> candidate.target().toUrl()
                .contains("/instances/" + id + "/")).findFirst().orElseThrow();
            assertThat(item.title().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
                .as("step 3b: the item says the start failed").isEqualTo("audit-restart could not be started");
            assertThat(item.detail().key()).as("step 3b: the item's detail says in words what the failure meant")
                .isEqualTo("start_failed_worded");
            assertThat(item.detail().resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
                .as("step 3b: in words that say what happened")
                .isEqualTo("It never got to run.");
            // The failure's own message is the technical line under those words, never the sentence itself.
            assertThat(Objects.requireNonNull(item.note(), "step 3b: the item carries the technical line")
                    .resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver()))
                .as("step 3b: the technical line is the failure's own message")
                .startsWith("Technically: ")
                .doesNotContain("exited unexpectedly");

            FakeNativeDaemons.daemonOf(hostId).remove(handle);
        });
    }

    @Test
    void everySurfacePowerOperationLandsInTheActivityLogExactlyOnce() {
        Db.run(datasource, () -> {
            assertThat(HistoryTestSupport.recordsOptional())
                .as("optional history must be recording or this test proves nothing")
                .isTrue();

            // 1. The SERVICE is the funnel: a settled deploy records itself, attributed
            //    to whoever was acting, naming the daemon handle it settled against.
            int serviceId = instanceRecord("audit-service");
            InstanceService service = new InstanceService();
            Accountability.runAs(RecordedChanges.operator(SERVICE_OPERATOR), () -> service.deploy(serviceId));

            List<RecordedChanges.Recorded> deployed = activityFor(serviceId, HohenheimActivityAction.DEPLOYED);
            assertThat(deployed)
                .as("step 1: a settled deploy records EXACTLY ONE deployed fact"
                    + " (the outcome write is a fenced updateAll and fires no hooks)")
                .hasSize(1);
            RecordedChanges.Recorded deployRow = deployed.get(0);
            assertThat(deployRow.actor())
                .as("step 1: the action names the acting principal")
                .isEqualTo(SERVICE_OPERATOR.toString());
            assertThat(deployRow.origin())
                .as("step 1: and the surface it came from")
                .isEqualTo(AccountabilityOrigin.WEB.token());
            assertThat(deployRow.fact(HohenheimActivityAction.DEPLOYED))
                .as("step 1: and the daemon handle the operation settled against")
                .containsEntry(HohenheimActivityAction.HANDLE_KEY, FakeNativeDaemons.handleOf(serviceId));

            // 2. Stop is the same contract, not a second policy.
            Accountability.runAs(RecordedChanges.operator(SERVICE_OPERATOR), () -> service.stop(serviceId));
            List<RecordedChanges.Recorded> stopped = activityFor(serviceId, HohenheimActivityAction.STOPPED);
            assertThat(stopped).as("step 2: a settled stop records once").hasSize(1);
            assertThat(stopped.get(0).origin())
                .as("step 2: with the same attribution the deploy carried")
                .isEqualTo(AccountabilityOrigin.WEB.token());

            // 3. THE HOLE THIS TEST EXISTS FOR: the admin panel's own row actions. They
            //    call the service and return a toast; before the fix they wrote nothing,
            //    so the same operation was audited over /api/v1 and silent from the UI
            //    -- including from /manage, where the delegated tenant lives.
            int panelId = instanceRecord("audit-panel");
            var panel = PanelEntryViews.of(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCES);
            Row panelRow = Models.get(InstanceModel.class).findById(panelId);
            PanelAction<Row> deployAction = PlacedActionClicks.placed(panel, "deploy_instance");
            PanelAction<Row> stopAction = PlacedActionClicks.placed(panel, "stop_instance");

            Accountability.runAs(RecordedChanges.operator(PANEL_OPERATOR),
                () -> PlacedActionClicks.click(deployAction, panelRow));
            List<RecordedChanges.Recorded> panelDeploy = activityFor(panelId, HohenheimActivityAction.DEPLOYED);
            assertThat(panelDeploy)
                .withFailMessage("step 3: the PANEL deploy row action must be as answerable"
                    + " as the API one; found %s deployed facts", panelDeploy.size())
                .hasSize(1);
            assertThat(panelDeploy.get(0).actor())
                .as("step 3: attributed to the operator who clicked, not to the system")
                .isEqualTo(PANEL_OPERATOR.toString());

            Accountability.runAs(RecordedChanges.operator(PANEL_OPERATOR),
                () -> PlacedActionClicks.click(stopAction, panelRow));
            List<RecordedChanges.Recorded> panelStop = activityFor(panelId, HohenheimActivityAction.STOPPED);
            assertThat(panelStop)
                .withFailMessage("step 3: the PANEL stop row action must record too;"
                    + " found %s stopped facts", panelStop.size())
                .hasSize(1);

            // 4. Destroy is irreversible, so it must never read as a generic update: the trash write every destroy
            //    caller (the release engine, preview expiry, database teardown) shares carries core's trashed fact.
            int destroyId = instanceRecord("audit-destroy");
            Accountability.runAs(RecordedChanges.operator(SERVICE_OPERATOR), () -> {
                service.deploy(destroyId);
                new InstanceService().destroy(destroyId);
            });
            List<RecordedChanges.Recorded> destroyed = activityFor(destroyId, ZenitActivityAction.TRASHED);
            assertThat(destroyed)
                .as("step 4: a service-lane destroy is recorded as one trash")
                .hasSize(1);
            assertThat(bareUpdates(destroyId))
                .as("step 4: and NOT additionally as a bare update")
                .isEmpty();

            // 5. The panel's delete path reaches the same recording, with no wrapper of
            //    its own: one destroy, one delete row, whoever asked for it.
            int panelDestroyId = instanceRecord("audit-panel-destroy");
            Row toDelete = Models.get(InstanceModel.class).findById(panelDestroyId);
            Accountability.runAs(RecordedChanges.operator(PANEL_OPERATOR), () -> {
                service.deploy(panelDestroyId);
                OperationWrites.delete(PanelRegistry.getBySlug(HohenheimSlugs.ADMIN), panel, toDelete,
                    TestAccessContexts.allAllowed());
            });
            List<RecordedChanges.Recorded> panelDestroyed = activityFor(panelDestroyId, ZenitActivityAction.TRASHED);
            assertThat(panelDestroyed)
                .as("step 5: the panel destroy records exactly one trash, the verb the service lane uses")
                .hasSize(1);
            assertThat(panelDestroyed.get(0).actor())
                .as("step 5: attributed to the operator, not the system")
                .isEqualTo(PANEL_OPERATOR.toString());
        });
    }
}
