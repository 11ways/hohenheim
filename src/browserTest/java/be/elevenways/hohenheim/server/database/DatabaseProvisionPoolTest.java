package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.OperatorIdentity;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.SystemPrincipal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provisioning pool runs every task as system work on its SUBMITTER's behalf (a tenant's allocation stays the
 * tenant's action, and work nobody asked for stays the system's), and a record's job waits for and re-reads its record.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class DatabaseProvisionPoolTest {

    private static final Duration BOUND = Duration.ofSeconds(20);

    @BeforeAll
    static void boot() {
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void poolWorkIsSystemAuthorityAttributedToWhoeverSubmittedIt() throws Exception {
        // 1. A tenant submits: the task runs with system authority, attributed to the tenant.
        UserPrincipal tenant = new UserPrincipal(Uuids.v7(), "Pool Tenant");
        CompletableFuture<List<Object>> tenantTask = new CompletableFuture<>();
        TenantConduits.as(tenant, () -> DatabaseService.submit(() -> tenantTask.complete(observed())));
        assertThat(tenantTask.get(10, TimeUnit.SECONDS))
            .as("step 1: system authority, the tenant's attribution")
            .containsExactly(true, tenant.reference());

        // 2. A thread with no caller submits: the task is attributed to the typed system principal.
        CompletableFuture<List<Object>> systemTask = new CompletableFuture<>();
        ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE,
            () -> DatabaseService.submit(() -> systemTask.complete(observed())));
        assertThat(systemTask.get(10, TimeUnit.SECONDS))
            .as("step 2: system authority, system attribution")
            .containsExactly(true, SystemPrincipal.INSTANCE.reference());
    }

    @Test
    void aDatabaseDestroyedWhileItsProvisioningWaitsGetsNoEngineInstance() throws Exception {
        Model databases = Models.get(DatabaseModel.class);
        Row[] stored = new Row[1];
        ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE, () -> stored[0] = provisioningRecord(databases));
        Row record = stored[0];
        int recordId = record.get(DatabaseModel.ID);

        // 1. A destroy holds the record's content lease; the provisioning job handed over meanwhile waits for it.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> destroy = new CompletableFuture<>();
        JobRunner.startVirtualThread(() -> ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE, () -> {
            try {
                DatabaseContentLock.exclusive(recordId, InstanceOperationLock.Contention.REFUSE, () -> {
                    holding.countDown();
                    Poll.until("the test releases the destroy", BOUND, () -> release.getCount() == 0);
                    // A destroy's last step, inside the lease: the record goes.
                    databases.find().where(DatabaseModel.ID.eq(recordId)).delete();
                    return null;
                });
                destroy.complete(null);
            } catch (Throwable failure) {
                destroy.completeExceptionally(failure);
            }
        }));
        assertThat(holding.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)).as("step 1: the destroy holds the lease")
            .isTrue();
        CompletableFuture<Void> job = new DatabaseService().provisionInBackground(recordId);
        Poll.until("step 1: the provisioning job waits for the record's content lease", BOUND,
            () -> DatabaseContentLock.isAwaited(recordId));

        // 2. The destroy ends; the job then finds the record gone and provisions nothing on its host.
        release.countDown();
        destroy.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        job.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(databases.findById(recordId)).as("step 2: the record stays destroyed").isNull();
        assertThat(DatabaseInstances.ownedBy(EngineHost.dedicated(record)))
            .as("step 2: no engine instance was created for the destroyed record").isNull();
    }

    /** A dedicated record stored as provisioning on the local host, as a create persists it before its job. */
    private static Row provisioningRecord(Model databases) {
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, "pool-destroyed-" + Uuids.v7());
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cr3t-pool-destroyed-pw");
        row.set(DatabaseModel.EPHEMERAL, true);
        row.set(DatabaseModel.PLACEMENT, DatabaseModel.PLACEMENT_DEDICATED);
        row.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        databases.save(row);
        return row;
    }

    /** Whether the pool thread is system work, and its typed attribution. */
    private static List<Object> observed() {
        Accountability attribution = Accountability.current();
        return List.of(ExecutionIdentity.isSystem(),
            attribution.actorReference());
    }
}
