package be.elevenways.hohenheim.test.stack;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.cms.StackOperations;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.render.action.CmsConfirmation;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.zenit.common.orm.history.ActionModel;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.security.SystemPrincipal;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.standalone();

    private static SqlDatasource datasource;
    private static StackRuntime runtime;
    private static final UUID DEPLOY_OPERATOR = Uuids.v7();
    private static final UUID ROLLBACK_OPERATOR = Uuids.v7();
    private static final UUID TENANT = Uuids.v7();

    @BeforeAll
    static void setUp() throws Exception {
        FieldEncryption.installKeyring(EncryptionKeyring.loadOrCreate(
            Files.createTempDirectory("hh-stack-audit").resolve("keys.dry")));
        datasource = APP.database();
        // ONE registered database per class: the controller identity every daemon
        // resource name resolves through reads the CURRENT datasource.
        runtime = new StackRuntime(new DockerClient(), datasource);
    }

    @Test
    void everyStackOperationIsAnsweredByAnActivityRowNamingItsOperator() throws IOException {
        assertThat(HistoryTestSupport.recordsOptional())
            .as("optional history must be recording or this test proves nothing")
            .isTrue();
        int stackId = stackRecord("audit-stack");

        // 1. The SYNCHRONOUS door (adoption, scripts, tests). It hops onto the stack's
        //    worker exactly like the panel does, so it is the same carry under test.
        Accountability.runAs(RecordedChanges.operator(DEPLOY_OPERATOR), () -> deployQuietly(stackId, "manual"));
        RecordedChanges.Recorded deployed = onlyActivity(stackId, HohenheimActivityAction.DEPLOYED);
        assertThat(Map.of(
                "actor", String.valueOf(deployed.actor()),
                "origin", String.valueOf(deployed.origin()),
                "trigger", String.valueOf(deployed.fact(HohenheimActivityAction.DEPLOYED)
                    .get(HohenheimActivityAction.TRIGGER_KEY))))
            .as("step 1: a settled deploy names the operator, the surface and the reason")
            .isEqualTo(Map.of("actor", DEPLOY_OPERATOR.toString(), "origin", AccountabilityOrigin.WEB.token(),
                "trigger", "manual"));

        // 2. A rollback is its OWN verb, not a second deploy row: an operator reading the
        //    trail must be able to tell "released forward" from "put back".
        Accountability.runAs(RecordedChanges.operator(ROLLBACK_OPERATOR), () -> {
            try {
                runtime.rollback(stackId);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        RecordedChanges.Recorded rolledBack = onlyActivity(stackId, HohenheimActivityAction.ROLLED_BACK);
        assertThat(rolledBack.actor())
            .as("step 2: attributed to whoever rolled back, not to whoever deployed")
            .isEqualTo(ROLLBACK_OPERATOR.toString());
        assertThat(activityFor(stackId, HohenheimActivityAction.DEPLOYED))
            .as("step 2: and a rollback does NOT also count as a forward deploy")
            .hasSize(1);

        // 3. Stop is the same contract, not a second policy: one stopped fact on the stack it settled against.
        Accountability.runAs(RecordedChanges.operator(ROLLBACK_OPERATOR), () -> {
            try {
                runtime.stop(stackId);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        RecordedChanges.Recorded stopped = onlyActivity(stackId, HohenheimActivityAction.STOPPED);
        assertThat(stopped.actor())
            .as("step 3: the stop is attributed to its operator")
            .isEqualTo(ROLLBACK_OPERATOR.toString());

        // 4. THE HOLE THIS TEST EXISTS FOR: the panel's own placed deploy operation, which is ASYNC. The
        //    attribution must survive the queue AND the worker thread, or the row lands as system work with no
        //    actor -- an accountability-shaped no-op.
        int panelId = stackRecord("audit-panel-stack");
        UUID clicker = Db.supply(datasource, () -> {
            TenantConduits.operatorUser();
            UUID id = ApiSupport.user("stack-clicker@hohenheim.local", "Stack Clicker");
            GrantService.createDirectGrant(GrantSubjectType.USER, id, "*", true);
            return id;
        });
        Db.run(datasource, () -> Accountability.runAs(RecordedChanges.operator(clicker),
                () -> PanelResourceCalls.invoke(
            HohenheimSlugs.ADMIN, HohenheimSlugs.STACKS, StackOperations.DEPLOY.id(), panelId,
            CmsConfirmation.PLAIN_PROOF,
            AccessContext.of(TenantConduits.stubFor(new UserPrincipal(clicker, "Stack Clicker"))))));
        await("step 4: the queued panel deploy settles",
            () -> activityFor(panelId, HohenheimActivityAction.DEPLOYED).size() == 1);
        RecordedChanges.Recorded panelDeploy = onlyActivity(panelId, HohenheimActivityAction.DEPLOYED);
        assertThat(Map.of(
                "actor", String.valueOf(panelDeploy.actor()),
                "origin", String.valueOf(panelDeploy.origin())))
            .as("step 4: the operator who clicked survived the queue and the worker thread")
            .isEqualTo(Map.of("actor", String.valueOf(clicker), "origin", AccountabilityOrigin.WEB.token()));

        // 5. ORIGIN is what tells the surfaces apart, so an unattended caller (stack
        //    adoption, boot recovery) must record as system rather than borrow an actor.
        //    The unattended work is declared the way a boot thread is: system work by a
        //    declared purpose. Its actor is the system principal, told apart from an
        //    operator account by its own id and kind.
        int systemId = stackRecord("audit-system-stack");
        ExecutionIdentity.runDetachedAsSystem(HohenheimSystemPurposes.STACK, () -> deployQuietly(systemId, "adoption"));
        RecordedChanges.Recorded systemDeploy = onlyActivity(systemId, HohenheimActivityAction.DEPLOYED);
        PrincipalRef system = SystemPrincipal.INSTANCE.reference();
        assertThat(Map.of(
                "actor", String.valueOf(systemDeploy.actor()),
                "actor_kind", String.valueOf((Object) systemDeploy.action().get(ActionModel.ACTOR_KIND)),
                "purpose", String.valueOf((Object) systemDeploy.action().get(ActionModel.SYSTEM_PURPOSE)),
                "origin", String.valueOf(systemDeploy.origin()),
                "trigger", String.valueOf(systemDeploy.fact(HohenheimActivityAction.DEPLOYED)
                    .get(HohenheimActivityAction.TRIGGER_KEY))))
            .as("step 5: unattended work is recorded as system work under the purpose that declared it"
                + " (never the stack lane that raised it again), with its own trigger")
            .isEqualTo(Map.of("actor", String.valueOf(system.id()), "actor_kind", system.storedKind(),
                "purpose", HohenheimSystemPurposes.STACK.id().toString(),
                "origin", AccountabilityOrigin.SYSTEM.token(),
                "trigger", "adoption"));

        // 6. A TENANT's request, whose attribution is its caller identity rather than an
        //    entered scope: the worker runs it as system work on that tenant's behalf.
        int tenantStackId = stackRecord("audit-tenant-stack");
        TenantConduits.as(new UserPrincipal(TENANT, "Stack Tenant"),
            () -> deployQuietly(tenantStackId, "manual"));
        assertThat(onlyActivity(tenantStackId, HohenheimActivityAction.DEPLOYED).actor())
            .as("step 6: a tenant-started stack deploy is the tenant's action, never SYSTEM")
            .isEqualTo(TENANT.toString());
    }

    // -- fixture --------------------------------------------------------------

    private static void deployQuietly(int stackId, String reason) {
        try {
            runtime.deploy(stackId, reason);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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

    private static List<RecordedChanges.Recorded> activityFor(int stackId, HohenheimActivityAction kind) {
        return read(() -> RecordedChanges.withFact(Models.get(StackModel.class), stackId, kind));
    }

    private static RecordedChanges.Recorded onlyActivity(int stackId, HohenheimActivityAction kind) {
        List<RecordedChanges.Recorded> rows = activityFor(stackId, kind);
        assertThat(rows)
            .withFailMessage("expected EXACTLY ONE '%s' fact on stack %s; found %s"
                + " (0 means the operation is unanswerable, which is what this test guards)",
                kind.id(), stackId, rows.size())
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
