package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.instance.InstanceDeviceQuota;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The disk-GB and extra-NIC quotas on the device-row write funnel: the reservation is
 * ADJACENT to the write (a beforeWrite hook over the core ledger), so two racing
 * attaches cannot both spend the last GB / slot; a resize charges only the DELTA; a
 * hard delete releases through the remove-hook pairing. Asserts STATE (rows, the
 * stored used counters) and the NAMED refusal identities, never bare failure.
 */
class InstanceDeviceQuotaTest extends HohenheimTestBase {

    private static final String DISK_BUCKET = InstanceDeviceQuota.diskBucketOf("");
    private static final String NIC_BUCKET = InstanceDeviceQuota.nicBucketOf("");
    private static final String NAME_PREFIX = "devq-";
    private static final String FORM_OWNER = "devq-form-owner";

    private Integer instanceId;
    private Integer quotaRowId;
    private Integer previousDiskCap;
    private Integer previousNicCap;

    @AfterEach
    void cleanUp() {
        if (this.quotaRowId != null) {
            Models.get(InstanceQuotaModel.class).delete(this.quotaRowId);
            this.quotaRowId = null;
        }
        Model devices = Models.get(InstanceDeviceModel.class);
        for (Row row : devices.find().where(InstanceDeviceModel.NAME.startsWith(NAME_PREFIX)).all()) {
            devices.delete(row.get(InstanceDeviceModel.ID));
        }
        if (this.instanceId != null) {
            Models.get(InstanceModel.class).delete(this.instanceId);
            this.instanceId = null;
        }
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
            this.previousDiskCap == null ? 0 : this.previousDiskCap);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER,
            this.previousNicCap == null ? 0 : this.previousNicCap);
    }

    private int instanceRecord() {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "devq-owner");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        Models.get(InstanceModel.class).save(row);
        this.instanceId = row.get(InstanceModel.ID);
        return this.instanceId;
    }

    private Row diskRow(int instanceId, String name, int sizeGb) {
        Row row = Models.get(InstanceDeviceModel.class).createEmptyRow();
        row.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        row.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_DISK);
        row.set(InstanceDeviceModel.NAME, name);
        row.set(InstanceDeviceModel.SIZE_GB, sizeGb);
        return row;
    }

    private static String violationKeyOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }

    @Test
    void racingDiskAttachesCannotOverspendAndResizeChargesTheDelta() throws Exception {
        this.previousDiskCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER);
        int instanceId = instanceRecord();
        long usedBefore = Quotas.usedOf(DISK_BUCKET);
        // EXACTLY 2 GB of headroom above whatever the shared server already counts.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
            (int) usedBefore + 2);

        // 1. Two threads behind a barrier both write a 2 GB disk row: only one can win
        //    the last 2 GB -- the ledger's guarded statement is the enforcement.
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable>[] failures = new AtomicReference[] {
            new AtomicReference<>(), new AtomicReference<>()};
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int slot = i;
            Thread worker = new Thread(() -> {
                try {
                    barrier.await();
                    Models.get(InstanceDeviceModel.class)
                        .save(diskRow(instanceId, NAME_PREFIX + "race" + slot, 2));
                } catch (Throwable refused) {
                    failures[slot].set(refused);
                }
            });
            worker.start();
            threads.add(worker);
        }
        for (Thread thread : threads) {
            thread.join();
        }

        List<Row> raceRows = Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.NAME.startsWith(NAME_PREFIX + "race")).all();
        assertThat(raceRows)
            .as("step 1: exactly ONE racing 2 GB attach landed").hasSize(1);
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 1: the ledger counts exactly the winner's 2 GB")
            .isEqualTo(usedBefore + 2);
        Throwable loser = failures[0].get() != null ? failures[0].get() : failures[1].get();
        assertThat(violationKeyOf(loser))
            .as("step 1: the loser got the NAMED disk-quota refusal")
            .isEqualTo("disk_quota_reached");
        Row winner = raceRows.get(0);
        assertThat((String) winner.get(InstanceDeviceModel.QUOTA_BUCKET))
            .as("step 1: the charged bucket is stamped on the row")
            .isEqualTo(DISK_BUCKET);

        // 2. Shrinking 2 -> 1 releases the delta.
        winner.set(InstanceDeviceModel.SIZE_GB, 1);
        Models.get(InstanceDeviceModel.class).save(winner);
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 2: the shrink released 1 GB").isEqualTo(usedBefore + 1);

        // 3. Growing 1 -> 4 needs 3 GB against 1 GB of headroom: the NAMED refusal,
        //    and the ledger is untouched by the refused write.
        winner.set(InstanceDeviceModel.SIZE_GB, 4);
        Throwable grow = catchThrowable(() ->
            Models.get(InstanceDeviceModel.class).save(winner));
        assertThat(violationKeyOf(grow))
            .as("step 3: over-cap grow is the named refusal").isEqualTo("disk_quota_reached");
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 3: the refused grow spent nothing").isEqualTo(usedBefore + 1);
        assertThat((Integer) Models.get(InstanceDeviceModel.class)
                .findById(winner.get(InstanceDeviceModel.ID)).get(InstanceDeviceModel.SIZE_GB))
            .as("step 3: the stored size is still the pre-grow 1 GB").isEqualTo(1);

        // 4. Hard delete releases through the remove-hook pairing: back to the start.
        Models.get(InstanceDeviceModel.class).delete(winner.get(InstanceDeviceModel.ID));
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 4: the delete handed the reservation back").isEqualTo(usedBefore);
    }

    @Test
    void nicSlotsAreReservedAndInvalidDevicesAreRefusedByName() throws Exception {
        this.previousNicCap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER);
        int instanceId = instanceRecord();
        long usedBefore = Quotas.usedOf(NIC_BUCKET);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER,
            (int) usedBefore + 1);

        // 1. The last NIC slot is spendable once.
        Row nic = Models.get(InstanceDeviceModel.class).createEmptyRow();
        nic.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        nic.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_NIC);
        nic.set(InstanceDeviceModel.NAME, NAME_PREFIX + "nic1");
        Models.get(InstanceDeviceModel.class).save(nic);
        assertThat(Quotas.usedOf(NIC_BUCKET))
            .as("step 1: the NIC slot is counted").isEqualTo(usedBefore + 1);

        // 2. A second NIC is the named refusal, and nothing landed.
        Row second = Models.get(InstanceDeviceModel.class).createEmptyRow();
        second.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        second.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_NIC);
        second.set(InstanceDeviceModel.NAME, NAME_PREFIX + "nic2");
        Throwable refused = catchThrowable(() ->
            Models.get(InstanceDeviceModel.class).save(second));
        assertThat(violationKeyOf(refused))
            .as("step 2: the second NIC gets the named refusal").isEqualTo("nic_quota_reached");
        assertThat(Quotas.usedOf(NIC_BUCKET))
            .as("step 2: the refused NIC spent nothing").isEqualTo(usedBefore + 1);

        // 3. The reserved device names and a sizeless disk are refused BEFORE any
        //    reservation (beforeValidate), each by name.
        long diskUsedBefore = Quotas.usedOf(DISK_BUCKET);
        for (String bad : new String[] {"root", "eth0", "Bad_Name"}) {
            Throwable invalid = catchThrowable(() ->
                Models.get(InstanceDeviceModel.class).save(diskRow(instanceId, bad, 1)));
            assertThat(violationKeyOf(invalid))
                .as("step 3: device name '%s' is refused by name", bad)
                .isEqualTo("device_name_invalid");
        }
        Throwable sizeless = catchThrowable(() ->
            Models.get(InstanceDeviceModel.class).save(diskRow(instanceId, NAME_PREFIX + "z", 0)));
        assertThat(violationKeyOf(sizeless))
            .as("step 3: a zero-size disk is refused by name").isEqualTo("device_size_invalid");
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 3: none of the refused writes reached the disk ledger")
            .isEqualTo(diskUsedBefore);

        // 4. Delete releases the slot.
        Models.get(InstanceDeviceModel.class).delete(nic.get(InstanceDeviceModel.ID));
        assertThat(Quotas.usedOf(NIC_BUCKET))
            .as("step 4: the NIC slot came back").isEqualTo(usedBefore);
    }

    /**
     * The per-owner disk/NIC overrides M073 created are reachable by an operator: the
     * generated admin form must carry every cap column the reserve hooks read, or the
     * column is enforced-but-unsettable (the silent-success shape -- the form reports
     * success while dropping the field it never declared).
     */
    @Test
    void perOwnerCapsSubmittedThroughTheAdminFormAreWhatTheReserveHooksRead() throws Exception {
        Model quotas = Models.get(InstanceQuotaModel.class);

        // 1. Create through the resource form, with both device caps.
        var created = postForm("/admin/instance-quotas/new",
            "subjects=" + FORM_OWNER + "&max_instances=3&max_disk_gb=7&max_nics=2");
        assertThat(created.statusCode())
            .as("step 1: the quota form accepted the submission").isIn(200, 302, 303);

        Row row = quotas.find().where(InstanceQuotaModel.SUBJECTS.eq(FORM_OWNER)).first();
        assertThat(row).as("step 1: the override row landed").isNotNull();
        this.quotaRowId = row.get(InstanceQuotaModel.ID);
        assertThat((Integer) row.get(InstanceQuotaModel.MAX_DISK_GB))
            .as("step 1: the submitted disk cap is STORED, not dropped by the form")
            .isEqualTo(7);
        assertThat((Integer) row.get(InstanceQuotaModel.MAX_NICS))
            .as("step 1: the submitted NIC cap is STORED, not dropped by the form")
            .isEqualTo(2);

        // 2. The enforcement half reads exactly what the operator submitted.
        assertThat(InstanceDeviceQuota.diskLimitFor(FORM_OWNER))
            .as("step 2: the disk reserve hook honours the form-written override")
            .isEqualTo(7);
        assertThat(InstanceDeviceQuota.nicLimitFor(FORM_OWNER))
            .as("step 2: the NIC reserve hook honours the form-written override")
            .isEqualTo(2);

        // 3. An UPDATE to 0 must survive as 0 -- "this owner gets nothing" is a
        //    different answer from "no override, fall through to the global default".
        var updated = postForm("/admin/instance-quotas/" + this.quotaRowId,
            "subjects=" + FORM_OWNER + "&max_instances=3&max_disk_gb=0&max_nics=0");
        assertThat(updated.statusCode())
            .as("step 3: the quota form accepted the update").isIn(200, 302, 303);
        assertThat(InstanceDeviceQuota.diskLimitFor(FORM_OWNER))
            .as("step 3: an override of 0 stays 0 and does not fall through to the default")
            .isEqualTo(0);
        assertThat(InstanceDeviceQuota.nicLimitFor(FORM_OWNER))
            .as("step 3: the NIC override of 0 stays 0")
            .isEqualTo(0);
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
