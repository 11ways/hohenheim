package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.IncusPreflight;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.cms.common.action.ActionContext;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static SqliteDatasource datasource;
    private static int hostId;

    /** The panel's own action builders are protected; the panel is the subject here. */
    private static final class ExposedInstanceResource extends InstanceResource {
        @Override public RowAction<Row> deployAction() { return super.deployAction(); }
        @Override public RowAction<Row> stopAction() { return super.stopAction(); }
    }

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-power-audit-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE registered database per class: the controller identity every daemon
        // resource name resolves through reads the CURRENT datasource.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = incusHost("audit-host"));
    }

    /** An admitted, tenant-accepting host with the stored preflight placement demands. */
    private static int incusHost(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostPreflight.store(name, new HostPreflight.Report(List.of(
            new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "fake daemon"),
            new HostPreflight.Check(IncusPreflight.KERNEL_LANE_CHECK,
                HostPreflight.STATUS_PASS, true, "fake kernel-truth lane")),
            Map.of("mem_total", 16L * 1024 * 1024 * 1024), true, Instant.now(), null));
        return Models.get(ServerModel.class).findByName(name).get(ServerModel.ID);
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

    @Test
    void everySurfacePowerOperationLandsInTheActivityLogExactlyOnce() {
        Db.run(datasource, () -> {
            assertThat(ActivityLog.isInstalled())
                .as("the activity log must be installed or this test proves nothing")
                .isTrue();

            // 1. The SERVICE is the funnel: a settled deploy records itself, attributed
            //    to whoever was acting, naming the daemon handle it settled against.
            int serviceId = instanceRecord("audit-service");
            InstanceService service = new InstanceService();
            Accountability.runAs(operator("7"), () -> service.deploy(serviceId));

            List<Row> deployed = activityFor(serviceId, InstanceService.ACTIVITY_DEPLOY_ACTION);
            assertThat(deployed)
                .as("step 1: a settled deploy writes EXACTLY ONE activity row"
                    + " (the outcome write is a fenced updateAll and fires no hooks)")
                .hasSize(1);
            Row deployRow = deployed.get(0);
            assertThat((String) deployRow.get(ActivityModel.ACTOR))
                .as("step 1: the row names the acting principal")
                .isEqualTo("7");
            assertThat((String) deployRow.get(ActivityModel.ORIGIN))
                .as("step 1: and the surface it came from")
                .isEqualTo(Accountability.ORIGIN_WEB);
            assertThat((String) deployRow.get(ActivityModel.DETAIL))
                .as("step 1: and the daemon handle the operation settled against")
                .isEqualTo(FakeNativeDaemons.handleOf(serviceId));

            // 2. Stop is the same contract, not a second policy.
            Accountability.runAs(operator("7"), () -> service.stop(serviceId));
            List<Row> stopped = activityFor(serviceId, InstanceService.ACTIVITY_STOP_ACTION);
            assertThat(stopped).as("step 2: a settled stop records once").hasSize(1);
            assertThat((String) stopped.get(0).get(ActivityModel.ORIGIN))
                .as("step 2: with the same attribution the deploy carried")
                .isEqualTo(Accountability.ORIGIN_WEB);

            // 3. THE HOLE THIS TEST EXISTS FOR: the admin panel's own row actions. They
            //    call the service and return a toast; before the fix they wrote nothing,
            //    so the same operation was audited over /api/v1 and silent from the UI
            //    -- including from /manage, where the delegated tenant lives.
            int panelId = instanceRecord("audit-panel");
            ExposedInstanceResource panel = new ExposedInstanceResource();
            Row panelRow = Models.get(InstanceModel.class).findById(panelId);
            ActionContext ctx = ActionContext.of(AccessContext.anonymous());
            RowAction.Invoke<Row> deployAction = (RowAction.Invoke<Row>) panel.deployAction();
            RowAction.Invoke<Row> stopAction = (RowAction.Invoke<Row>) panel.stopAction();

            Accountability.runAs(operator("42"),
                () -> deployAction.handler().apply(panelRow, ctx));
            List<Row> panelDeploy = activityFor(panelId, InstanceService.ACTIVITY_DEPLOY_ACTION);
            assertThat(panelDeploy)
                .withFailMessage("step 3: the PANEL deploy row action must be as answerable"
                    + " as the API one; found %s activity rows", panelDeploy.size())
                .hasSize(1);
            assertThat((String) panelDeploy.get(0).get(ActivityModel.ACTOR))
                .as("step 3: attributed to the operator who clicked, not to the system")
                .isEqualTo("42");

            Accountability.runAs(operator("42"),
                () -> stopAction.handler().apply(panelRow, ctx));
            List<Row> panelStop = activityFor(panelId, InstanceService.ACTIVITY_STOP_ACTION);
            assertThat(panelStop)
                .withFailMessage("step 3: the PANEL stop row action must record too;"
                    + " found %s activity rows", panelStop.size())
                .hasSize(1);

            // 4. Destroy is irreversible, so it must never read as a generic update. The
            //    rename lives in the service, which is why a destroy the CMS never sees
            //    (the release engine, preview expiry, database teardown) says the same
            //    thing as one an operator clicked.
            int destroyId = instanceRecord("audit-destroy");
            Accountability.runAs(operator("7"), () -> {
                service.deploy(destroyId);
                new InstanceService().destroy(destroyId);
            });
            List<Row> destroyed = activityFor(destroyId, ActivityLog.ACTION_DELETE);
            assertThat(destroyed)
                .as("step 4: a service-lane destroy is recorded as a DELETE, not an update")
                .hasSize(1);
            assertThat((String) destroyed.get(0).get(ActivityModel.DETAIL))
                .as("step 4: named as the verified destroy it is")
                .isEqualTo(InstanceService.ACTIVITY_DESTROY_DETAIL);
            assertThat(activityFor(destroyId, ActivityLog.ACTION_UPDATE))
                .as("step 4: and NOT additionally as a bare update -- the soft delete's"
                    + " own hook row is the one that got renamed, not a second row")
                .isEmpty();

            // 5. The panel's delete path reaches the same recording, with no wrapper of
            //    its own: one destroy, one delete row, whoever asked for it.
            int panelDestroyId = instanceRecord("audit-panel-destroy");
            Row toDelete = Models.get(InstanceModel.class).findById(panelDestroyId);
            Accountability.runAs(operator("42"), () -> {
                service.deploy(panelDestroyId);
                panel.deleteRow(toDelete, AccessContext.anonymous());
            });
            List<Row> panelDestroyed = activityFor(panelDestroyId, ActivityLog.ACTION_DELETE);
            assertThat(panelDestroyed)
                .as("step 5: the panel destroy records exactly one delete row")
                .hasSize(1);
            assertThat((String) panelDestroyed.get(0).get(ActivityModel.DETAIL))
                .as("step 5: under the same verb the service lane uses")
                .isEqualTo(InstanceService.ACTIVITY_DESTROY_DETAIL);
            assertThat((String) panelDestroyed.get(0).get(ActivityModel.ACTOR))
                .as("step 5: attributed to the operator, not the system")
                .isEqualTo("42");
        });
    }
}
