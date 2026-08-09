package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.cms.StackResource;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.stack.StackRuntime;
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
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stack accountability as a daemon-free journey: the SAME operation must be answerable
 * whichever surface performed it, and the answer must name the operator.
 *
 * AIDEV-NOTE: this exists because the stack tier was the LAST silent one -- deploy,
 * rollback, stop and the data-destroying volume purge wrote no activity row on any
 * surface, so "who took this stack down" had no answer at all. What made it hard is
 * proven here rather than described: every stack operation runs on the stack's own
 * worker thread, and {@code Accountability} resolves through a ThreadLocal, so a row
 * written there is unattributed {@code system} work unless the dispatching thread's
 * attribution is snapshotted and re-entered. The stacks used here declare ZERO services
 * on purpose: the accountability contract is not a Docker fact, and a test that needs a
 * daemon to prove it would never run.
 */
class StackAuditTest {

    private static SqliteDatasource datasource;
    private static StackRuntime runtime;

    @BeforeAll
    static void setUp() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-stack-audit").resolve("keys.dry")));
        File db = File.createTempFile("hohenheim-stack-audit-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE registered database per class: the controller identity every daemon
        // resource name resolves through reads the CURRENT datasource.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        runtime = new StackRuntime(new DockerClient(), datasource);
    }

    @Test
    void everyStackOperationIsAnsweredByAnActivityRowNamingItsOperator() throws IOException {
        assertThat(ActivityLog.isInstalled())
            .as("the activity log must be installed or this test proves nothing")
            .isTrue();
        int stackId = stackRecord("audit-stack");

        // 1. The SYNCHRONOUS door (adoption, scripts, tests). It hops onto the stack's
        //    worker exactly like the panel does, so it is the same carry under test.
        Accountability.runAs(operator("7"), () -> deployQuietly(stackId, "manual"));
        Row deployed = onlyActivity(stackId, StackRuntime.ACTIVITY_DEPLOY_ACTION);
        assertThat(Map.of(
                "actor", String.valueOf((Object) deployed.get(ActivityModel.ACTOR)),
                "origin", String.valueOf((Object) deployed.get(ActivityModel.ORIGIN)),
                "detail", String.valueOf((Object) deployed.get(ActivityModel.DETAIL))))
            .as("step 1: a settled deploy names the operator, the surface and the reason")
            .isEqualTo(Map.of("actor", "7", "origin", Accountability.ORIGIN_WEB,
                "detail", "manual"));

        // 2. A rollback is its OWN verb, not a second deploy row: an operator reading the
        //    trail must be able to tell "released forward" from "put back".
        Accountability.runAs(operator("42"), () -> {
            try {
                runtime.rollback(stackId);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        Row rolledBack = onlyActivity(stackId, StackRuntime.ACTIVITY_ROLLBACK_ACTION);
        assertThat((String) rolledBack.get(ActivityModel.ACTOR))
            .as("step 2: attributed to whoever rolled back, not to whoever deployed")
            .isEqualTo("42");
        assertThat(activityFor(stackId, StackRuntime.ACTIVITY_DEPLOY_ACTION))
            .as("step 2: and a rollback does NOT also count as a forward deploy")
            .hasSize(1);

        // 3. Stop is the same contract, not a second policy, and it names the stack it
        //    settled against (the instance tier's detail convention).
        Accountability.runAs(operator("42"), () -> {
            try {
                runtime.stop(stackId);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        Row stopped = onlyActivity(stackId, StackRuntime.ACTIVITY_STOP_ACTION);
        assertThat((String) stopped.get(ActivityModel.DETAIL))
            .as("step 3: the stop names the stack it settled against")
            .isEqualTo("audit-stack");

        // 4. THE HOLE THIS TEST EXISTS FOR: the panel's own row action, which is ASYNC.
        //    The attribution must survive the queue AND the worker thread, or the row
        //    lands as system work with no actor -- an accountability-shaped no-op.
        int panelId = stackRecord("audit-panel-stack");
        RowAction.Invoke<Row> deployAction = (RowAction.Invoke<Row>) new StackResource()
            .rowActions().stream()
            .filter(action -> "deploy_stack".equals(action.id().getPath()))
            .findFirst().orElseThrow();
        Row panelRow = read(() -> Models.get(StackModel.class).findById(panelId));
        ActionContext ctx = ActionContext.of(AccessContext.anonymous());
        Accountability.runAs(operator("99"),
            () -> deployAction.handler().apply(panelRow, ctx));
        await("step 4: the queued panel deploy settles",
            () -> activityFor(panelId, StackRuntime.ACTIVITY_DEPLOY_ACTION).size() == 1);
        Row panelDeploy = onlyActivity(panelId, StackRuntime.ACTIVITY_DEPLOY_ACTION);
        assertThat(Map.of(
                "actor", String.valueOf((Object) panelDeploy.get(ActivityModel.ACTOR)),
                "origin", String.valueOf((Object) panelDeploy.get(ActivityModel.ORIGIN))))
            .as("step 4: the operator who clicked survived the queue and the worker thread")
            .isEqualTo(Map.of("actor", "99", "origin", Accountability.ORIGIN_WEB));

        // 5. ORIGIN is what tells the surfaces apart, so an unattended caller (stack
        //    adoption, boot recovery) must record as system rather than borrow an actor.
        int systemId = stackRecord("audit-system-stack");
        deployQuietly(systemId, "adoption");
        Row systemDeploy = onlyActivity(systemId, StackRuntime.ACTIVITY_DEPLOY_ACTION);
        assertThat(Map.of(
                "actor", String.valueOf((Object) systemDeploy.get(ActivityModel.ACTOR)),
                "origin", String.valueOf((Object) systemDeploy.get(ActivityModel.ORIGIN)),
                "detail", String.valueOf((Object) systemDeploy.get(ActivityModel.DETAIL))))
            .as("step 5: unattended work is recorded as system work, named by its reason")
            .isEqualTo(Map.of("actor", "null",
                "origin", Accountability.ORIGIN_SYSTEM, "detail", "adoption"));
    }

    // -- fixture --------------------------------------------------------------

    private static void deployQuietly(int stackId, String reason) {
        try {
            runtime.deploy(stackId, reason);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Accountability operator(String id) {
        return new Accountability(id, "Operator " + id, "10.0.0.1", "junit",
            Accountability.ORIGIN_WEB);
    }

    private static int stackRecord(String name) {
        return read(() -> {
            Row stack = Models.get(StackModel.class).createEmptyRow();
            stack.set(StackModel.NAME, name);
            stack.set(StackModel.ENABLED, true);
            stack.set(StackModel.SERVER_ID, ServerModel.localServerId());
            Models.get(StackModel.class).save(stack);
            return (Integer) stack.get(StackModel.ID);
        });
    }

    private static List<Row> activityFor(int stackId, String action) {
        return read(() -> Models.get(ActivityModel.class).find()
            .where(ActivityModel.MODEL.eq(StackModel.MODEL_ID.toString()))
            .where(ActivityModel.RECORD_ID.eq(String.valueOf(stackId)))
            .where(ActivityModel.ACTION.eq(action))
            .orderBy(ActivityModel.ID, SortOrder.DESC)
            .all());
    }

    private static Row onlyActivity(int stackId, String action) {
        List<Row> rows = activityFor(stackId, action);
        assertThat(rows)
            .withFailMessage("expected EXACTLY ONE '%s' activity row on stack %s; found %s"
                + " (0 means the operation is unanswerable, which is what this test guards)",
                action, stackId, rows.size())
            .hasSize(1);
        return rows.get(0);
    }

    private static <T> T read(Supplier<T> body) {
        Object[] out = new Object[1];
        Db.run(datasource, () -> out[0] = body.get());
        @SuppressWarnings("unchecked")
        T typed = (T) out[0];
        return typed;
    }

    /** Bounded wait: an async row action settles on the stack's worker, not inline. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
