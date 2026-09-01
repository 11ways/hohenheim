package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.quota.QuotaReconciler;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A REFUSED instance write must not leave the owner charged for a workload that does not
 * exist -- and where a before-write hook structurally cannot compensate, the reconcile
 * lane moves the ledger back to what the live rows say.
 *
 * AIDEV-NOTE: this is the production defect of 2026-09-01 in a test. Three hooks spend
 * against ONE instance write (the owner's slot, the owner's memory, the host's memory) and
 * an instance save carries no ambient transaction, so a write refused after the first hook
 * has spent leaves that spend behind with no record to release it. Robbedoes read
 * {@code owner_mem_mb} exactly 512 MB above the sum of its live bookings and
 * {@code instances} 15 against 14 live rows, with every {@code host_mem_mb} bucket correct
 * -- the signature of an owner-side spend followed by a HOST-side refusal, which is step 3
 * below. Step 2 is the half that IS locally fixable and now is.
 *
 * AIDEV-NOTE: step 3 asserts a leak still happens, deliberately. It is not a blessing of
 * the defect: it is the fact that pins WHY the reconciler exists, and it is what makes
 * step 4 a real proof rather than a no-op over an already-correct ledger. If a later
 * change makes the sibling refusal compensate itself, this step is the one to rewrite --
 * never delete it silently, or step 4 stops testing anything.
 *
 * Its OWN datasource, so every bucket asserted here is this class's alone. No daemon is
 * contacted: every decision is over stored record state.
 */
class QuotaDriftReconcileTest {

    private static final String PREFIX = "drift-";
    private static final String DOCKER_KIND = "hohenheim:docker_container";

    /** DockerContainerKind.defaultFootprintMb -- what an unbounded container is admitted as. */
    private static final int FOOTPRINT_MB = 512;

    private static final String MEMORY_BUCKET = InstanceQuota.memoryBucketOf("");
    private static final String COUNT_BUCKET = InstanceQuota.bucketKeyOf("");

    private static SqlDatasource datasource;

    private final List<Integer> instances = new ArrayList<>();
    private final List<Integer> hosts = new ArrayList<>();
    private Integer previousMemoryCap;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB, 0);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO, 1.0);
    }

    @AfterEach
    void cleanUp() {
        Db.run(datasource, () -> {
            for (Integer id : this.instances) {
                Models.get(InstanceModel.class).delete(id);
            }
            for (Integer id : this.hosts) {
                Models.get(ServerModel.class).delete(id);
            }
        });
        this.instances.clear();
        this.hosts.clear();
        if (this.previousMemoryCap != null) {
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER, this.previousMemoryCap);
            this.previousMemoryCap = null;
        }
    }

    @Test
    void aRefusedCreateNeverLeavesAnOwnerChargedForAWorkloadThatDoesNotExist() {
        this.previousMemoryCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER);
        Db.run(datasource, () -> {
            int roomy = host("roomy", 65536L);
            int tiny = host("tiny", 256L);

            // 1. A workload that LANDS charges both owner dimensions -- the anchor every
            //    later step is measured against.
            int landed = save(workload(roomy, "landed", null));
            assertThat(Quotas.usedOf(COUNT_BUCKET))
                .as("step 1: the owner holds one slot").isEqualTo(1);
            assertThat(Quotas.usedOf(MEMORY_BUCKET))
                .as("step 1: and the workload's memory").isEqualTo(FOOTPRINT_MB);

            // 2. A create the OWNER MEMORY cap refuses spends NOTHING. The slot is reserved
            //    before the memory is, so an uncompensated refusal cost the owner one
            //    instance of their cap per refusal, forever -- a lockout that only grows.
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER, FOOTPRINT_MB);
            Throwable overOwnerBudget =
                catchThrowable(() -> Models.get(InstanceModel.class)
                    .save(workload(roomy, "over-owner", 4096)));
            assertThat(violationKeyOf(overOwnerBudget))
                .as("step 2: refused by the owner memory budget, by name")
                .isEqualTo("memory_quota_reached");
            assertThat(Quotas.usedOf(COUNT_BUCKET))
                .as("step 2: and the slot it had already spent came back")
                .isEqualTo(1);
            assertThat(Quotas.usedOf(MEMORY_BUCKET))
                .as("step 2: the memory never moved").isEqualTo(FOOTPRINT_MB);
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Quota.MAX_MEMORY_MB_PER_OWNER, 0);

            // 3. THE RESIDUE, and the shape robbedoes carried: the HOST budget refuses, and
            //    it does so in a LATER hook -- so the owner's slot and memory are already
            //    spent and no before-write hook can hand them back. The host bucket itself
            //    is untouched, which is exactly what the production ledger showed.
            Throwable overHostBudget =
                catchThrowable(() -> Models.get(InstanceModel.class)
                    .save(workload(tiny, "over-host", 4096)));
            assertThat(violationKeyOf(overHostBudget))
                .as("step 3: refused by the host capacity gate, by name")
                .isEqualTo("host_capacity_reached");
            assertThat(liveNamed(PREFIX + "over-host"))
                .as("step 3: and no row landed").isEmpty();
            assertThat(Quotas.usedOf(COUNT_BUCKET))
                .as("step 3: the owner is left holding a slot for it -- the leak")
                .isEqualTo(2);
            assertThat(Quotas.usedOf(MEMORY_BUCKET))
                .as("step 3: and its memory too")
                .isEqualTo(FOOTPRINT_MB + 4096);
            assertThat(InstanceCapacity.bookedMbOn(tiny))
                .as("step 3: while the host that refused booked nothing, which is why the"
                    + " production symptom was owner-only").isZero();

            // 4. THE RECONCILE: truth is what the live rows say, and both drifted buckets
            //    are named and corrected. This is the lane that heals a control plane
            //    whose ledger already drifted, at its next boot.
            QuotaReconciler.Result result = QuotaReconciler.reconcile();
            assertThat(result.abstained())
                .as("step 4: nothing moved under the scan, so it really wrote").isFalse();
            assertThat(result.corrections())
                .as("step 4: it named the two drifted buckets")
                .extracting(QuotaReconciler.Correction::bucket)
                .containsExactlyInAnyOrder(COUNT_BUCKET, MEMORY_BUCKET);
            assertThat(Quotas.usedOf(COUNT_BUCKET))
                .as("step 4: the slot count is back to the live rows").isEqualTo(1);
            assertThat(Quotas.usedOf(MEMORY_BUCKET))
                .as("step 4: and the owner memory with it").isEqualTo(FOOTPRINT_MB);

            // 5. IDEMPOTENCE: a healthy ledger is left alone. A reconcile that "corrects"
                //  a correct bucket would be the over-release that zeroes a bucket and
                //  wipes every other live workload's booking in it.
            assertThat(QuotaReconciler.reconcile().corrections())
                .as("step 5: a healthy ledger is corrected in no place").isEmpty();

            // 6. A bucket whose last workload is GONE reconciles to zero. The trashed row
            //    is what names the bucket -- computing candidates from live rows alone
            //    would leave such a leak invisible forever.
            Row trashed = Models.get(InstanceModel.class).findById(landed);
            trashed.set(InstanceModel.DELETED_AT, Instant.now());
            Models.get(InstanceModel.class).save(trashed);
            Quotas.reserve(MEMORY_BUCKET, 777, Long.MAX_VALUE);
            assertThat(QuotaReconciler.reconcile().corrections())
                .as("step 6: the orphaned leak is named")
                .extracting(QuotaReconciler.Correction::bucket)
                .containsExactly(MEMORY_BUCKET);
            assertThat(Quotas.usedOf(MEMORY_BUCKET))
                .as("step 6: and a bucket no live row charges goes back to zero").isZero();
            assertThat(Quotas.usedOf(COUNT_BUCKET))
                .as("step 6: the soft delete had already handed the slot back honestly")
                .isZero();
        });
    }

    // -- helpers --------------------------------------------------------------

    private int host(String name, long memoryMb) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        int id = row.get(ServerModel.ID);
        this.hosts.add(id);
        HostPreflight.store(PREFIX + name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, memoryMb * 1024L * 1024L),
            true, Instant.now(), null));
        return id;
    }

    private Row workload(int serverId, String name, Integer memoryMb) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, PREFIX + name);
        row.set(InstanceModel.KIND, DOCKER_KIND);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/image");
        if (memoryMb != null) {
            settings.put("memory_limit_mb", memoryMb);
        }
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, serverId);
        return row;
    }

    private int save(Row row) {
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        this.instances.add(id);
        return id;
    }

    private static List<Row> liveNamed(String name) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.NAME.eq(name))
            .and(InstanceModel.DELETED_AT.isNull())
            .all();
    }

    private static String violationKeyOf(Throwable thrown) {
        assertThat(thrown)
            .as("the write was refused with Violations (a write that SUCCEEDS here means the"
                + " gate under test let it through)")
            .isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }
}
