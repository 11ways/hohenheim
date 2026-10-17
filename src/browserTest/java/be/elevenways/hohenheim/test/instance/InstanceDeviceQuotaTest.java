package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.InstanceQuotaParts;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.thread.ExecutionContext;
import be.elevenways.zenit.auth.cms.SubjectTag;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.PermissionGroupModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    private static final String DISK_BUCKET = OwnerBudget.DISK_GB.bucketOf("");
    private static final String NIC_BUCKET = OwnerBudget.NICS.bucketOf("");
    private static final String NAME_PREFIX = "devq-";
    /** The slug of the role this test's override names: the owner picker offers only real accounts and groups. */
    private static final String FORM_OWNER_ROLE = "devq-form-owner";
    /** The slug prefix of the groups that share the owner this test's shared override names. */
    private static final String SHARED_OWNER_ROLE = "devq-shared-owner";

    private Integer instanceId;
    private Integer quotaRowId;
    private Integer quickQuotaRowId;
    private UUID ownerGroupId;
    private final List<UUID> sharedGroupIds = new ArrayList<>();
    private Integer previousDiskCap;
    private Integer previousNicCap;

    @AfterEach
    void cleanUp() {
        if (this.quotaRowId != null) {
            Models.get(InstanceQuotaModel.class).delete(this.quotaRowId);
            this.quotaRowId = null;
        }
        if (this.quickQuotaRowId != null) {
            Models.get(InstanceQuotaModel.class).delete(this.quickQuotaRowId);
            this.quickQuotaRowId = null;
        }
        if (this.ownerGroupId != null) {
            Models.get(PermissionGroupModel.class).delete(this.ownerGroupId);
            this.ownerGroupId = null;
        }
        for (UUID group : this.sharedGroupIds) {
            RecordGrants.revokeAllForSubject(GrantSubjectType.GROUP, group);
            Models.get(PermissionGroupModel.class).delete(group);
        }
        this.sharedGroupIds.clear();
        Model devices = Models.get(InstanceDeviceModel.class);
        for (Row row : devices.find().where(InstanceDeviceModel.NAME.startsWith(NAME_PREFIX)).all()) {
            devices.delete(row.get(InstanceDeviceModel.ID));
        }
        if (this.instanceId != null) {
            HardDeletes.byId(Models.get(InstanceModel.class), this.instanceId);
            this.instanceId = null;
        }
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
            this.previousDiskCap == null ? 0 : this.previousDiskCap);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER,
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
        this.previousDiskCap = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER);
        int instanceId = instanceRecord();
        long usedBefore = Quotas.usedOf(DISK_BUCKET);
        // EXACTLY 2 GB of headroom above whatever the shared server already counts.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER,
            (int) usedBefore + 2);

        // 1. Two threads behind a barrier both write a 2 GB disk row: only one can win
        //    the last 2 GB -- the ledger's guarded statement is the enforcement.
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable>[] failures = new AtomicReference[] {
            new AtomicReference<>(), new AtomicReference<>()};
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int slot = i;
            Thread worker = new Thread(ExecutionContext.wrap(() -> {
                try {
                    barrier.await();
                    Models.get(InstanceDeviceModel.class)
                        .save(diskRow(instanceId, NAME_PREFIX + "race" + slot, 2));
                } catch (Throwable refused) {
                    failures[slot].set(refused);
                }
            }));
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
        this.previousNicCap = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER);
        int instanceId = instanceRecord();
        long usedBefore = Quotas.usedOf(NIC_BUCKET);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER,
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

        // 5. A type token no DeviceType member declares is REFUSED and charges nothing:
        //    the create hook used to charge nothing for it and let it land.
        Row unknown = Models.get(InstanceDeviceModel.class).createEmptyRow();
        unknown.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        unknown.set(InstanceDeviceModel.TYPE, "floppy");
        unknown.set(InstanceDeviceModel.NAME, NAME_PREFIX + "floppy");
        Throwable unknownRefused = catchThrowable(() ->
            Models.get(InstanceDeviceModel.class).save(unknown));
        assertThat(unknownRefused)
            .as("step 5: an unknown device type is refused").isInstanceOf(Violations.class);
        assertThat(Models.get(InstanceDeviceModel.class).find()
                .where(InstanceDeviceModel.NAME.eq(NAME_PREFIX + "floppy")).count())
            .as("step 5: and nothing landed").isZero();
        assertThat(Quotas.usedOf(NIC_BUCKET))
            .as("step 5: no NIC slot was spent on it").isEqualTo(usedBefore);
        assertThat(Quotas.usedOf(DISK_BUCKET))
            .as("step 5: no disk GB was spent on it").isEqualTo(diskUsedBefore);

        // 6. A STORED row whose type drifted to an unknown token (a hook-free write, as an
        //    older or foreign writer could leave it) releases NOTHING on removal: the old
        //    delete path released one NIC slot for anything that was not a cdrom, handing
        //    out capacity nobody had spent.
        Row charged = Models.get(InstanceDeviceModel.class).createEmptyRow();
        charged.set(InstanceDeviceModel.INSTANCE_ID, instanceId);
        charged.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_NIC);
        charged.set(InstanceDeviceModel.NAME, NAME_PREFIX + "drifted");
        Models.get(InstanceDeviceModel.class).save(charged);
        assertThat(Quotas.usedOf(NIC_BUCKET))
            .as("step 6: the NIC charged its slot").isEqualTo(usedBefore + 1);
        Models.get(InstanceDeviceModel.class).find()
            .where(InstanceDeviceModel.ID.eq(charged.get(InstanceDeviceModel.ID)))
            .assign(InstanceDeviceModel.TYPE, "floppy")
            .updateAll();
        Models.get(InstanceDeviceModel.class).delete(charged.get(InstanceDeviceModel.ID));
        try {
            assertThat(Quotas.usedOf(NIC_BUCKET))
                .as("step 6: removing an unknown-typed row released nothing")
                .isEqualTo(usedBefore + 1);
        } finally {
            Quotas.release(NIC_BUCKET, 1);
        }
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
        Row role = Models.get(PermissionGroupModel.class).createEmptyRow();
        role.set(PermissionGroupModel.SLUG, FORM_OWNER_ROLE);
        role.set(PermissionGroupModel.TITLE, FORM_OWNER_ROLE);
        Models.get(PermissionGroupModel.class).save(role);
        this.ownerGroupId = role.get(PermissionGroupModel.ID);
        String owner = new SubjectTag(GrantSubjectType.GROUP, this.ownerGroupId).token();

        // 1. Create through the resource form, the owner picked, with both device caps.
        var created = adminPostForm("/admin/instance-quotas/new",
            "subjects=" + owner + "&max_instances=3&max_disk_gb=7&max_nics=2");
        assertThat(created.statusCode())
            .as("step 1: the quota form accepted the submission").isIn(200, 302, 303);

        Row row = quotas.find().where(InstanceQuotaModel.SUBJECTS.eq(owner)).first();
        assertThat(row).as("step 1: the override row landed").isNotNull();
        this.quotaRowId = row.get(InstanceQuotaModel.ID);
        assertThat((Integer) row.get(InstanceQuotaModel.MAX_DISK_GB))
            .as("step 1: the submitted disk cap is STORED, not dropped by the form")
            .isEqualTo(7);
        assertThat((Integer) row.get(InstanceQuotaModel.MAX_NICS))
            .as("step 1: the submitted NIC cap is STORED, not dropped by the form")
            .isEqualTo(2);

        // 2. The enforcement half reads exactly what the operator submitted.
        assertThat(OwnerBudget.DISK_GB.limitFor(owner))
            .as("step 2: the disk reserve hook honours the form-written override")
            .isEqualTo(7);
        assertThat(OwnerBudget.NICS.limitFor(owner))
            .as("step 2: the NIC reserve hook honours the form-written override")
            .isEqualTo(2);

        // 3. An UPDATE to 0 must survive as 0 -- "this owner gets nothing" is a
        //    different answer from "no override, fall through to the global default".
        var updated = adminPostForm("/admin/instance-quotas/" + this.quotaRowId,
            "subjects=" + owner + "&max_instances=3&max_disk_gb=0&max_nics=0");
        assertThat(updated.statusCode())
            .as("step 3: the quota form accepted the update").isIn(200, 302, 303);
        assertThat(OwnerBudget.DISK_GB.limitFor(owner))
            .as("step 3: an override of 0 stays 0 and does not fall through to the default")
            .isEqualTo(0);
        assertThat(OwnerBudget.NICS.limitFor(owner))
            .as("step 3: the NIC override of 0 stays 0")
            .isEqualTo(0);
    }

    /**
     * An owner several subjects share is ONE override: picked as two groups on the admin form's multi-select, stored as
     * the pack every bucket stamp is built from, read back as both subjects, and the cap its devices are charged
     * against. The single-subject picker this replaced could not name such an owner at all, and the list's quick add
     * picks one through the same multi-select.
     */
    @Test
    void aSharedOwnerPickedOnTheAdminFormIsTheCapItsDevicesAreChargedAgainst() throws Exception {
        String first = new SubjectTag(GrantSubjectType.GROUP, this.sharedGroup("a")).token();
        String second = new SubjectTag(GrantSubjectType.GROUP, this.sharedGroup("b")).token();
        String pack = HohenheimAccess.packSubjects(Set.of(first, second));
        Model quotas = Models.get(InstanceQuotaModel.class);

        // 1. Create with both subjects picked: the multi-select's repeated values and its always-present blank marker.
        var created = adminPostForm("/admin/instance-quotas/new", owners(first, second) + "&max_disk_gb=3");
        assertThat(created.statusCode())
            .as("step 1: the quota form accepted two owners").isIn(200, 302, 303);
        Row row = quotas.find().where(InstanceQuotaModel.SUBJECTS.eq(pack)).first();
        assertThat(row).as("step 1: the override stores the shared owner's one pack").isNotNull();
        this.quotaRowId = row.get(InstanceQuotaModel.ID);

        // 2. The edit form shows the stored owner as both subjects.
        assertThat(InstanceQuotaParts.admin().formValues(row).get(InstanceQuotaModel.SUBJECTS.getName()))
            .as("step 2: the edit form reads the shared owner back as its two subjects")
            .asList().containsExactlyInAnyOrder(first, second);

        // 3. A record both groups manage is charged in the shared owner's bucket, capped by the override.
        int instanceId = instanceRecord();
        RecordGrants.grant(GrantSubjectType.GROUP, this.sharedGroupIds.get(0), InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.MANAGE, true);
        RecordGrants.grant(GrantSubjectType.GROUP, this.sharedGroupIds.get(1), InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.MANAGE, true);
        String bucket = OwnerBudget.DISK_GB.bucketOf(pack);
        long usedBefore = Quotas.usedOf(bucket);
        Row disk = diskRow(instanceId, NAME_PREFIX + "shared1", 2);
        Models.get(InstanceDeviceModel.class).save(disk);
        assertThat((String) disk.get(InstanceDeviceModel.QUOTA_BUCKET))
            .as("step 3: the disk is stamped with the shared owner's bucket").isEqualTo(bucket);
        assertThat(Quotas.usedOf(bucket)).as("step 3: the shared owner is charged 2 GB").isEqualTo(usedBefore + 2);
        Throwable over = catchThrowable(() ->
            Models.get(InstanceDeviceModel.class).save(diskRow(instanceId, NAME_PREFIX + "shared2", 2)));
        assertThat(violationKeyOf(over))
            .as("step 3: 2 GB more passes the override's 3 GB: the named refusal").isEqualTo("disk_quota_reached");

        // 4. Editing the cap through the form keeps the shared owner, and the raised cap is what the next charge meets.
        var updated = adminPostForm("/admin/instance-quotas/" + this.quotaRowId,
            owners(first, second) + "&max_disk_gb=4");
        assertThat(updated.statusCode()).as("step 4: the quota form accepted the edit").isIn(200, 302, 303);
        assertThat((String) quotas.findById(this.quotaRowId).get(InstanceQuotaModel.SUBJECTS))
            .as("step 4: the edit kept the shared owner's pack").isEqualTo(pack);
        Models.get(InstanceDeviceModel.class).save(diskRow(instanceId, NAME_PREFIX + "shared3", 2));
        assertThat(Quotas.usedOf(bucket))
            .as("step 4: the raised cap admitted 2 GB more").isEqualTo(usedBefore + 4);

        // 5. The list's quick add picks a shared owner on the same multi-select: two subjects, one cap, one pack, and
        //    the picks kept on the list it returns to.
        String third = new SubjectTag(GrantSubjectType.GROUP, this.sharedGroup("c")).token();
        String quickPack = HohenheimAccess.packSubjects(Set.of(first, third));
        navigateToApp("/admin/instance-quotas");
        page.locator("pl-button[data-cms-quick-add-open]").click();
        String quick = "[data-cms-quick-add] ";
        page.locator(quick + "[data-zf-create-field='subjects'] .pl-select-field").click();
        String popup = "he-bottom .pl-select-popup[data-open] [role='option']";
        page.locator(popup + "[data-value='" + first + "']").click();
        page.locator(popup + "[data-value='" + third + "']").click();
        assertThat(page.locator(quick + "[data-zf-create-field='subjects'] pl-select-chip").count())
            .as("step 5: both picked subjects are chips").isEqualTo(2);
        // A multiple pick keeps its popup open over the sheet; Escape closes that topmost layer, not the sheet.
        page.keyboard().press("Escape");
        page.waitForCondition(() -> page.locator("he-bottom .pl-select-popup[data-open]").count() == 0);
        assertThat(page.locator(quick + "[data-zf-create-field='subjects'] pl-select-chip").count())
            .as("step 5: closing the picker keeps both picks").isEqualTo(2);
        page.locator(quick + "[data-zf-create-field='max_instances'] input").fill("5");
        page.locator(quick + "[data-cms-quick-add-submit]").click();
        page.waitForCondition(() -> quotas.find().where(InstanceQuotaModel.SUBJECTS.eq(quickPack)).first() != null);
        Row quick5 = quotas.find().where(InstanceQuotaModel.SUBJECTS.eq(quickPack)).first();
        this.quickQuotaRowId = quick5.get(InstanceQuotaModel.ID);
        assertThat((Integer) quick5.get(InstanceQuotaModel.MAX_INSTANCES))
            .as("step 5: the quick add stored its cap on the shared owner's override").isEqualTo(5);
        page.waitForCondition(() -> page.url().contains("qa.subjects="));
        assertThat(page.url()).as("step 5: both picks ride the list URL the add returns to")
            .contains("qa.subjects=" + URLEncoder.encode(first, StandardCharsets.UTF_8))
            .contains("qa.subjects=" + URLEncoder.encode(third, StandardCharsets.UTF_8));
    }

    /**
     * A shared owner of five UUID subjects packs past the ledger's 191-character key, so its disks are stamped with the
     * folded digest bucket; its override still caps them, because an override is matched by that same bucket.
     */
    @Test
    void anOverrideOfAFiveSubjectOwnerCapsTheDisksChargedToItsFoldedBucket() {
        List<String> subjects = new ArrayList<>();
        for (String suffix : List.of("f1", "f2", "f3", "f4", "f5")) {
            subjects.add(new SubjectTag(GrantSubjectType.GROUP, this.sharedGroup(suffix)).token());
        }
        String pack = HohenheimAccess.packSubjects(Set.copyOf(subjects));
        String bucket = OwnerBudget.DISK_GB.bucketOf(pack);

        // 1. Five UUID subjects fold: the bucket is the digest spelling, not the readable pack.
        assertThat(bucket).as("step 1: the five-subject owner's disk bucket is folded")
            .startsWith(OwnerBudget.DISK_GB.prefix() + "sha256:");

        // 2. The owner's override, stored under its readable pack, is the cap read through the digest spelling its
        //    folded bucket names (what a charge knows of its owner).
        Model quotas = Models.get(InstanceQuotaModel.class);
        Row quota = quotas.createEmptyRow();
        quota.set(InstanceQuotaModel.SUBJECTS, pack);
        quota.set(InstanceQuotaModel.MAX_DISK_GB, 3);
        quotas.save(quota);
        this.quotaRowId = quota.get(InstanceQuotaModel.ID);
        assertThat(OwnerBudget.DISK_GB.limitFor(OwnerBudget.DISK_GB.packOf(bucket)))
            .as("step 2: the folded bucket reads the owner's 3 GB override").isEqualTo(3);

        // 3. A disk of a record all five manage is stamped with, and charged to, the folded bucket.
        int instanceId = instanceRecord();
        for (UUID group : this.sharedGroupIds) {
            RecordGrants.grant(GrantSubjectType.GROUP, group, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.MANAGE, true);
        }
        long usedBefore = Quotas.usedOf(bucket);
        Row disk = diskRow(instanceId, NAME_PREFIX + "fold1", 2);
        Models.get(InstanceDeviceModel.class).save(disk);
        assertThat((String) disk.get(InstanceDeviceModel.QUOTA_BUCKET))
            .as("step 3: the disk is stamped with the folded bucket").isEqualTo(bucket);
        assertThat(Quotas.usedOf(bucket)).as("step 3: the owner is charged 2 GB").isEqualTo(usedBefore + 2);

        // 4. 2 GB more passes the override's 3 GB: refused by name, nothing booked.
        Throwable over = catchThrowable(() ->
            Models.get(InstanceDeviceModel.class).save(diskRow(instanceId, NAME_PREFIX + "fold2", 2)));
        assertThat(violationKeyOf(over))
            .as("step 4: the override caps the folded bucket").isEqualTo("disk_quota_reached");
        assertThat(Quotas.usedOf(bucket)).as("step 4: the refused disk booked nothing").isEqualTo(usedBefore + 2);
    }

    /** @return a fresh permission group, removed with its grants after the test */
    private UUID sharedGroup(String suffix) {
        Row group = Models.get(PermissionGroupModel.class).createEmptyRow();
        group.set(PermissionGroupModel.SLUG, SHARED_OWNER_ROLE + "-" + suffix);
        group.set(PermissionGroupModel.TITLE, SHARED_OWNER_ROLE + "-" + suffix);
        Models.get(PermissionGroupModel.class).save(group);
        UUID id = group.get(PermissionGroupModel.ID);
        this.sharedGroupIds.add(id);
        return id;
    }

    /** @return the owner picker's transport: one value per picked subject, then the blank marker */
    private static String owners(String... subjects) {
        StringBuilder body = new StringBuilder();
        for (String subject : subjects) {
            body.append("subjects=").append(URLEncoder.encode(subject, StandardCharsets.UTF_8)).append('&');
        }
        return body.append("subjects=").toString();
    }
}
