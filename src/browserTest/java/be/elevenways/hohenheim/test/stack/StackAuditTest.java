package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.cms.StackOperations;
import be.elevenways.hohenheim.server.cms.StackParts;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.render.action.CmsConfirmation;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.SystemPrincipal;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
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

    private static SqlDatasource datasource;
    private static StackRuntime runtime;

    @BeforeAll
    static void setUp() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-stack-audit").resolve("keys.dry")));
        datasource = TestDatabases.freshDatasource();
        // ONE registered database per class: the controller identity every daemon
        // resource name resolves through reads the CURRENT datasource.
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
        Row deployed = onlyActivity(stackId, HohenheimActivityAction.DEPLOYED.id().toString());
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
        Row rolledBack = onlyActivity(stackId, HohenheimActivityAction.ROLLED_BACK.id().toString());
        assertThat((String) rolledBack.get(ActivityModel.ACTOR))
            .as("step 2: attributed to whoever rolled back, not to whoever deployed")
            .isEqualTo("42");
        assertThat(activityFor(stackId, HohenheimActivityAction.DEPLOYED.id().toString()))
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
        Row stopped = onlyActivity(stackId, HohenheimActivityAction.STOPPED.id().toString());
        assertThat((String) stopped.get(ActivityModel.DETAIL))
            .as("step 3: the stop names the stack it settled against")
            .isEqualTo("audit-stack");

        // 4. THE HOLE THIS TEST EXISTS FOR: the panel's own placed deploy operation, which is ASYNC. The
        //    attribution must survive the queue AND the worker thread, or the row lands as system work with no
        //    actor -- an accountability-shaped no-op.
        int panelId = stackRecord("audit-panel-stack");
        Db.run(datasource, () -> Accountability.runAs(operator("99"), () -> PanelResourceCalls.invoke(
            HohenheimSlugs.ADMIN, StackParts.SLUG, StackOperations.DEPLOY.id(), panelId,
            CmsConfirmation.PLAIN_PROOF, TenantConduits.operator())));
        await("step 4: the queued panel deploy settles",
            () -> activityFor(panelId, HohenheimActivityAction.DEPLOYED.id().toString()).size() == 1);
        Row panelDeploy = onlyActivity(panelId, HohenheimActivityAction.DEPLOYED.id().toString());
        assertThat(Map.of(
                "actor", String.valueOf((Object) panelDeploy.get(ActivityModel.ACTOR)),
                "origin", String.valueOf((Object) panelDeploy.get(ActivityModel.ORIGIN))))
            .as("step 4: the operator who clicked survived the queue and the worker thread")
            .isEqualTo(Map.of("actor", "99", "origin", Accountability.ORIGIN_WEB));

        // 5. ORIGIN is what tells the surfaces apart, so an unattended caller (stack
        //    adoption, boot recovery) must record as system rather than borrow an actor.
        //    The unattended work is declared the way a boot thread is: system work that is
        //    nobody's action. Its actor is the system principal, told apart from the
        //    operator account (also id 1 in this suite) by its kind.
        int systemId = stackRecord("audit-system-stack");
        ExecutionIdentity.runDetachedAsSystem("adoption", () -> deployQuietly(systemId, "adoption"));
        Row systemDeploy = onlyActivity(systemId, HohenheimActivityAction.DEPLOYED.id().toString());
        PrincipalRef system = SystemPrincipal.INSTANCE.reference();
        assertThat(Map.of(
                "actor", String.valueOf((Object) systemDeploy.get(ActivityModel.ACTOR)),
                "actor_kind", String.valueOf((Object) systemDeploy.get(ActivityModel.ACTOR_KIND)),
                "actor_label", String.valueOf((Object) systemDeploy.get(ActivityModel.ACTOR_LABEL)),
                "origin", String.valueOf((Object) systemDeploy.get(ActivityModel.ORIGIN)),
                "detail", String.valueOf((Object) systemDeploy.get(ActivityModel.DETAIL))))
            .as("step 5: unattended work is recorded as system work, labelled by the purpose that declared it"
                + " (never the stack lane that raised it again) and agreeing with its detail")
            .isEqualTo(Map.of("actor", String.valueOf(system.id()), "actor_kind", system.storedKind(),
                "actor_label", "adoption", "origin", Accountability.ORIGIN_SYSTEM, "detail", "adoption"));

        // 6. A TENANT's request, whose attribution is its caller identity rather than an
        //    entered scope: the worker runs it as system work on that tenant's behalf.
        int tenantStackId = stackRecord("audit-tenant-stack");
        TenantConduits.as(new UserPrincipal(4343, "Stack Tenant"),
            () -> deployQuietly(tenantStackId, "manual"));
        assertThat((String) onlyActivity(tenantStackId, HohenheimActivityAction.DEPLOYED.id().toString())
                .get(ActivityModel.ACTOR))
            .as("step 6: a tenant-started stack deploy is the tenant's action, never SYSTEM")
            .isEqualTo("4343");
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
        return new Accountability(id, PrincipalRef.account(Long.parseLong(id)).storedKind(), "Operator " + id,
            "10.0.0.1", "junit", Accountability.ORIGIN_WEB);
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
        Poll.until(what, Duration.ofSeconds(15), Duration.ofMillis(50), condition);
    }
}
