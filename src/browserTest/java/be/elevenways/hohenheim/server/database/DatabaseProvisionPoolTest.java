package be.elevenways.hohenheim.server.database;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.test.OperatorIdentity;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.protoblast.common.util.Uuids;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.SystemPrincipal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.io.UncheckedIOException;
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

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final Duration BOUND = Duration.ofSeconds(20);

    /** An image name no registry serves. */
    private static final String UNPULLABLE = "hohenheim-test/never-pulled:never";


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
        ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE,
            () -> stored[0] = provisioningRecord(databases, "postgres:16"));
        Row record = stored[0];
        int recordId = record.get(DatabaseModel.ID);

        // 1. A holder (a running dump, say) has the record's content lease; the provisioning job handed over meanwhile
        //    waits for it.
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch destroyNow = new CountDownLatch(1);
        CompletableFuture<Void> holder = new CompletableFuture<>();
        JobRunner.startVirtualThread(() -> ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE, () -> {
            try {
                DatabaseContentLock.exclusive(recordId, InstanceOperationLock.Contention.REFUSE, () -> {
                    holding.countDown();
                    Poll.until("the test lets the holder destroy", BOUND, () -> destroyNow.getCount() == 0);
                    // 3. The real destroy on the holder's own thread re-enters the lease and removes the record.
                    new DatabaseService().destroyById(recordId, false);
                    return null;
                });
                holder.complete(null);
            } catch (Throwable failure) {
                holder.completeExceptionally(failure);
            }
        }));
        assertThat(holding.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)).as("step 1: the holder has the lease")
            .isTrue();
        CompletableFuture<Void> job = new DatabaseService().provisionInBackground(recordId);
        Poll.until("step 1: the provisioning job waits for the record's content lease", BOUND,
            () -> DatabaseContentLock.isAwaited(recordId));

        // 2. The real destroy beside the holder takes the same lease, so it is refused and the record stays.
        Throwable[] refused = new Throwable[1];
        ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE, () -> {
            try {
                new DatabaseService().destroyById(recordId, false);
            } catch (Throwable failure) {
                refused[0] = failure;
            }
        });
        assertThat(refused[0]).as("step 2: a destroy beside the lease's holder is refused busy")
            .isInstanceOfSatisfying(DomainRefusal.class,
                refusal -> assertThat(refusal.reason()).isEqualTo(HohenheimRefusalReason.DATABASE_BUSY));
        assertThat(databases.findById(recordId)).as("step 2: and the record stays").isNotNull();

        // 3-4. The holder destroys the record and lets go; the job then finds it gone and provisions nothing.
        destroyNow.countDown();
        holder.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        job.get(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(databases.findById(recordId)).as("step 4: the record stays destroyed").isNull();
        assertThat(DatabaseInstances.ownedBy(EngineHost.dedicated(record)))
            .as("step 4: no engine instance was created for the destroyed record").isNull();
    }

    @Test
    void aRecordAStoppedControllerLeftProvisioningIsProvisionedAgainAtTheNextBoot() throws Exception {
        Model databases = Models.get(DatabaseModel.class);
        Row[] stored = new Row[2];
        // An image nobody can pull: the settled job's provisioning ends, failed, with or without a daemon.
        ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE, () -> {
            stored[0] = provisioningRecord(databases, UNPULLABLE);
            stored[1] = provisioningRecord(databases, UNPULLABLE);
        });
        int staleId = stored[0].get(DatabaseModel.ID);
        int liveId = stored[1].get(DatabaseModel.ID);
        try {
            // 1. One record was last written before this process started, by a controller whose stop left its job
            //    unfinished; the other is this process's own, a live job's.
            Instant before = BootSettle.processStart().minus(Duration.ofHours(1));
            databases.find().where(DatabaseModel.ID.eq(staleId))
                .assign(DatabaseModel.CREATED_AT, before).assign(DatabaseModel.UPDATED_AT, before).updateAll();
            assertThat(BootSettle.writtenByThisProcess(databases.findById(staleId).get(DatabaseModel.UPDATED_AT)))
                .as("step 1: the stale record reads as a stopped controller's").isFalse();

            // 2. The boot settle hands the stale record alone to the provisioning pool, which converges it.
            int[] handed = new int[1];
            ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE,
                () -> handed[0] = new DatabaseService().recoverInterruptedProvisioning());
            assertThat(handed[0]).as("step 2: one record was handed over").isEqualTo(1);
            assertThat(DatabaseService.awaitProvisioningIdle(BOUND)).as("step 2: the pool settled").isTrue();
            assertThat((String) databases.findById(staleId).get(DatabaseModel.STATUS))
                .as("step 2: the stale record is provisioning no longer").isEqualTo(DatabaseModel.STATUS_FAILED);
            assertThat((String) databases.findById(liveId).get(DatabaseModel.STATUS))
                .as("step 2: the live job's record is left to it").isEqualTo(DatabaseModel.STATUS_PROVISIONING);
        } finally {
            // Through the real destroy, which also ends any engine instance the settled job left.
            ExecutionIdentity.runDetachedAsSystem(OperatorIdentity.PURPOSE, () -> {
                try {
                    new DatabaseService().destroyById(staleId, false);
                    new DatabaseService().destroyById(liveId, false);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
        }
    }

    /** A dedicated record stored as provisioning on the local host, as a create persists it before its job. */
    private static Row provisioningRecord(Model databases, String image) {
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, "pool-record-" + Uuids.v7());
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.IMAGE, image);
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cr3t-pool-record-pw");
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
