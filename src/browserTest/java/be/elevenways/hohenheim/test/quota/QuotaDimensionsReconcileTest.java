package be.elevenways.hohenheim.test.quota;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.instance.InstanceDeviceQuota;
import be.elevenways.hohenheim.server.preview.PreviewQuota;
import be.elevenways.hohenheim.server.quota.DatabaseQuota;
import be.elevenways.hohenheim.server.quota.QuotaReconciler;
import be.elevenways.hohenheim.server.quota.SiteQuota;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every per-owner quota dimension is repairable, and a managed-database create that fails
 * after its quota hook spent a slot spends nothing.
 *
 * AIDEV-NOTE: step 2 stands in for the race it fixes. Two concurrent creates of one name
 * both passed the name check, both reserved in the before-write hook, and the loser's
 * unique-index failure left its slot spent forever; the check and the save now run in one
 * transaction, so a failure anywhere after the reservation rolls it back. The failure is
 * injected by a before-write hook registered AFTER the quota's (inert unless armed), which
 * is exactly where the unique index fires relative to the reservation.
 */
class QuotaDimensionsReconcileTest {

    private static final AtomicBoolean FAIL_NEXT_DATABASE_WRITE = new AtomicBoolean();
    private static final String STRANGER = "user:424242";

    @BeforeAll
    static void setUp() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        DatabaseModel.SCHEMA.addBeforeWriteHook(context -> {
            if (FAIL_NEXT_DATABASE_WRITE.getAndSet(false)) {
                throw new IllegalStateException("simulated unique-index loss after the reservation");
            }
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        FAIL_NEXT_DATABASE_WRITE.set(false);
        TestDatabases.freshDatabase();
    }

    @Test
    void aFailedCreateSpendsNothingAndEveryDimensionReconcilesToItsRows() {
        DatabaseService service = new DatabaseService();
        String databaseBucket = DatabaseQuota.bucketKeyOf("");
        long before = Quotas.usedOf(databaseBucket);

        // 1. A create that lands spends one database slot.
        service.insertRecord("recon-one", ManagedDatabase.Engine.POSTGRES, null, "app", "pw", "app",
            false, ServerService.LOCAL, ResourceLimits.none(), DatabaseModel.STATUS_ACTIVE,
            DatabaseModel.PLACEMENT_DEDICATED, null);
        assertThat(Quotas.usedOf(databaseBucket))
            .as("step 1: the landed create holds one slot").isEqualTo(before + 1);

        // 2. A create that fails AFTER the quota hook reserved rolls the reservation back
        //    with the insert: no row, no slot.
        FAIL_NEXT_DATABASE_WRITE.set(true);
        Throwable lost = catchThrowable(() -> service.insertRecord("recon-two",
            ManagedDatabase.Engine.POSTGRES, null, "app2", "pw", "app2", false, ServerService.LOCAL,
            ResourceLimits.none(), DatabaseModel.STATUS_ACTIVE, DatabaseModel.PLACEMENT_DEDICATED,
            null));
        assertThat(lost).as("step 2: the injected failure reached the caller").isNotNull();
        assertThat(Models.get(DatabaseModel.class).findByName("recon-two"))
            .as("step 2: no row landed").isNull();
        assertThat(Quotas.usedOf(databaseBucket))
            .as("step 2: and the slot the hook had reserved came back with the rollback")
            .isEqualTo(before + 1);

        // 3. Leaks in every dimension the reconciler used to skip: buckets no row names
        //    (the lost-race shape for an owner with no other record) and counts above the rows.
        Quotas.reserve(DatabaseQuota.bucketKeyOf(STRANGER), 1, Long.MAX_VALUE);
        Quotas.reserve(SiteQuota.bucketKeyOf(""), 2, Long.MAX_VALUE);
        Quotas.reserve(PreviewQuota.bucketKeyOf(STRANGER), 1, Long.MAX_VALUE);
        Quotas.reserve(InstanceDeviceQuota.nicBucketOf(STRANGER), 1, Long.MAX_VALUE);
        Quotas.reserve(InstanceDeviceQuota.diskBucketOf(STRANGER), 20, Long.MAX_VALUE);

        QuotaReconciler.Result result = QuotaReconciler.reconcile();
        assertThat(result.abstained()).as("step 3: nothing moved under the scan").isFalse();
        assertThat(Quotas.usedOf(DatabaseQuota.bucketKeyOf(STRANGER)))
            .as("step 3: a database bucket no record names goes to zero").isZero();
        assertThat(Quotas.usedOf(databaseBucket))
            .as("step 3: the operator's database bucket equals its records")
            .isEqualTo(operatorCount(DatabaseModel.class, DatabaseModel.QUOTA_BUCKET, databaseBucket, false));
        assertThat(Quotas.usedOf(SiteQuota.bucketKeyOf("")))
            .as("step 3: the operator's site bucket equals its live sites")
            .isEqualTo(operatorCount(SiteModel.class, SiteModel.QUOTA_BUCKET, SiteQuota.bucketKeyOf(""), true));
        assertThat(Quotas.usedOf(PreviewQuota.bucketKeyOf(STRANGER)))
            .as("step 3: a preview bucket no deployment names goes to zero").isZero();
        assertThat(Quotas.usedOf(InstanceDeviceQuota.nicBucketOf(STRANGER)))
            .as("step 3: and so do a NIC bucket").isZero();
        assertThat(Quotas.usedOf(InstanceDeviceQuota.diskBucketOf(STRANGER)))
            .as("step 3: and a disk bucket nothing is charged to").isZero();

        // 4. A second pass over an honest ledger moves nothing.
        assertThat(QuotaReconciler.reconcile().corrections())
            .as("step 4: the reconcile is idempotent").isEmpty();

        // 5. Cleanup through the real release path.
        Models.get(DatabaseModel.class).find().where(DatabaseModel.NAME.eq("recon-one")).delete();
    }

    /** How many rows of a model are charged to the operator bucket (stampless rows included). */
    private static long operatorCount(Class<? extends Model> model,
                                      StringField bucketField, String operatorBucket, boolean liveOnly) {
        long count = 0;
        for (Row row : Models.get(model).find().all()) {
            if (liveOnly && row.get("deleted_at") != null) {
                continue;
            }
            String stamp = row.get(bucketField);
            if (stamp == null || stamp.isBlank() || stamp.equals(operatorBucket)) {
                count++;
            }
        }
        return count;
    }
}
