package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceDeviceQuota;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.RootDisk;
import be.elevenways.hohenheim.server.preview.PreviewQuota;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.QuotaLedgerModel;
import be.elevenways.zenit.common.orm.quota.Quotas;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE drift correction for every per-owner quota dimension -- the instance tier's slot,
 * owner-memory and host-memory buckets, and the site, database, preview, extra-NIC and disk
 * (device plus root disk) buckets: what the rows say each bucket holds is the truth, and the
 * ledger is moved to it.
 *
 * AIDEV-NOTE: widened from the instance tier alone on 2026-09-23. A managed-database slot
 * leaked forever when two concurrent creates of one name both reserved and the loser's
 * insert failed (DatabaseService.insertRecord now runs in one transaction, so that shape
 * rolls back), and nothing could ever repair a leak in any dimension but the instance
 * one. Each dimension's truth is computed from the very stamps its release path hands back
 * ({@code quota_bucket}, {@code root_disk_bucket}, a stampless row falling to the operator
 * bucket of the same dimension exactly as the releases do), so a correction can never
 * disagree with what a later delete will release. Besides the buckets rows name, every
 * LEDGER row under a dimension's prefix is a candidate, so a leaked bucket no row names --
 * an owner whose only create was the one that failed -- is corrected to zero too.
 *
 * AIDEV-NOTE: this exists because a before-write hook cannot compensate a sibling hook's
 * refusal. Three hooks spend against a single instance write -- {@link InstanceQuota} (the
 * owner's slot and the owner's memory) and {@link InstanceCapacity} (the host's memory) --
 * and an instance save carries NO ambient transaction (Model.save opens one only for a
 * revisionable schema), so a write refused AFTER the first hook has spent leaves that
 * spend behind with no record to ever release it. The reachable shape is a create the HOST
 * budget refuses ({@code host_capacity_reached}): InstanceQuota has already booked the
 * owner's slot and memory, InstanceCapacity then throws, no row lands, and the owner is
 * charged for a workload that does not exist. Measured on production robbedoes 2026-09-01:
 * {@code owner_mem_mb} exactly 512 MB above the sum of live bookings and {@code instances}
 * 15 against 14 live rows, while every {@code host_mem_mb} bucket was correct -- the exact
 * signature of an owner-side spend followed by a host-side refusal.
 *
 * The narrow leaks are fixed where they are made (InstanceQuota now unwinds its own slot
 * when its own memory reservation refuses). This lane covers the residue, every future
 * shape of it, and the drift already sitting in a deployed ledger.
 *
 * TRUTH, precisely: a bucket's expected value is computed from LIVE instance rows only,
 * out of the very stamps the release paths hand back ({@code quota_memory_mb},
 * {@code capacity_mb}, {@code quota_bucket}), so a correction can never disagree with what
 * a later destroy will release. Candidate buckets come from EVERY instance row (trashed
 * included) and every host, so an owner whose last workload is gone is corrected to zero
 * rather than left holding a leak no live row could name.
 *
 * AIDEV-NOTE: the reconcile is DOUBLE-SCANNED and abstains on disagreement. A create that
 * lands between the row scan and the ledger read would otherwise be released as a leak, and
 * an over-release is the shape that ZEROES a bucket and wipes every other live workload's
 * booking in it -- the failure this whole class exists to prevent, arriving through its own
 * fix. Abstaining costs nothing: the next boot reconciles.
 *
 * Localization: bucket keys are machine tokens and every line here is an operator log.
 */
public final class QuotaReconciler {

    private QuotaReconciler() {
    }

    /** One bucket the ledger disagreed with the live rows about. */
    public record Correction(@NonNull String bucket, long was, long now) {}

    /**
     * The outcome of one reconcile.
     *
     * @param corrections every bucket that was moved, in scan order
     * @param abstained   true when the row set moved under the scan and NOTHING was written
     */
    public record Result(@NonNull List<Correction> corrections, boolean abstained) {}

    /**
     * Recompute every quota bucket from the rows and correct every one that disagrees.
     *
     * AIDEV-NOTE: the device and root-disk halves of the DISK bucket are one sum: both
     * {@code InstanceDeviceQuota} (a disk device's size, on any device row) and
     * {@code InstanceRootDiskQuota} (a LIVE instance's declared root GB) charge the same
     * owner bucket, so reconciling either alone would "correct" the other half away.
     */
    public static @NonNull Result reconcile() {
        Map<String, Long> expected = expectedBuckets();
        if (!expected.equals(expectedBuckets())) {
            Blast.log("QUOTA: reconcile abstained -- the rows moved under the scan;"
                + " the next boot reconciles");
            return new Result(List.of(), true);
        }
        List<Correction> corrections = new ArrayList<>();
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            String bucket = entry.getKey();
            long truth = entry.getValue();
            long used = Quotas.usedOf(bucket);
            if (used == truth) {
                continue;
            }
            if (used > truth) {
                Quotas.release(bucket, used - truth);
            } else {
                Quotas.reserve(bucket, truth - used, Long.MAX_VALUE);
            }
            corrections.add(new Correction(bucket, used, truth));
            Blast.log("QUOTA: reconciled bucket", bucket, "from", used, "to", truth,
                "- the ledger disagreed with the rows");
        }
        return new Result(corrections, false);
    }

    /** What every reachable bucket of every dimension SHOULD hold. */
    private static @NonNull Map<String, Long> expectedBuckets() {
        Map<String, Long> expected = new LinkedHashMap<>();
        for (String bucket : candidateBuckets()) {
            candidate(expected, bucket);
        }
        for (Row row : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DELETED_AT.isNull()).all()) {
            String countBucket = InstanceQuota.chargedBucketOf(row);
            add(expected, countBucket, 1);
            add(expected, InstanceQuota.memoryBucketOfChargedBucket(countBucket),
                ownerMemoryOf(row));
            Integer serverId = row.get(InstanceModel.SERVER_ID);
            if (serverId != null) {
                add(expected, InstanceCapacity.bucketOf(serverId), hostMemoryOf(row));
            }
            Integer rootGb = RootDisk.declaredGb(settingsOf(row));
            if (rootGb != null && rootGb > 0) {
                add(expected, stampedOr(row.get(InstanceModel.ROOT_DISK_BUCKET), diskOperatorBucket()),
                    rootGb);
            }
        }
        siteBuckets(expected);
        databaseBuckets(expected);
        previewBuckets(expected);
        deviceBuckets(expected);
        return expected;
    }

    /** One slot per LIVE site, in the bucket its stamp names. */
    private static void siteBuckets(@NonNull Map<String, Long> expected) {
        for (Row row : Models.get(SiteModel.class).find().all()) {
            String bucket = stampedOr(row.get(SiteModel.QUOTA_BUCKET), SiteQuota.bucketKeyOf(""));
            candidate(expected, bucket);
            if (row.get(SiteModel.DELETED_AT) == null) {
                add(expected, bucket, 1);
            }
        }
    }

    /** One slot per managed database record: databases have no soft delete. */
    private static void databaseBuckets(@NonNull Map<String, Long> expected) {
        for (Row row : Models.get(DatabaseModel.class).find().all()) {
            add(expected, stampedOr(row.get(DatabaseModel.QUOTA_BUCKET), DatabaseQuota.bucketKeyOf("")), 1);
        }
    }

    /** One slot per LIVE preview deployment, in the bucket its stamp names. */
    private static void previewBuckets(@NonNull Map<String, Long> expected) {
        for (Row row : Models.get(PreviewDeploymentModel.class).find().all()) {
            String bucket = stampedOr(row.get(PreviewDeploymentModel.QUOTA_BUCKET),
                PreviewQuota.bucketKeyOf(""));
            candidate(expected, bucket);
            if (row.get(PreviewDeploymentModel.DELETED_AT) == null) {
                add(expected, bucket, 1);
            }
        }
    }

    /**
     * Every device row's charge, by its type's DECLARED charge: a disk device its size in
     * the disk bucket, an extra NIC one slot. Devices are hard-deleted, so every row counts;
     * a token no type declares was charged nothing at create and counts nothing here.
     */
    private static void deviceBuckets(@NonNull Map<String, Long> expected) {
        for (Row row : Models.get(InstanceDeviceModel.class).find().all()) {
            DeviceType type = DeviceType.parse(row.get(InstanceDeviceModel.TYPE));
            if (type == null) {
                continue;
            }
            String stamp = row.get(InstanceDeviceModel.QUOTA_BUCKET);
            switch (type.charge()) {
                case DISK_GIGABYTES -> {
                    Integer size = row.get(InstanceDeviceModel.SIZE_GB);
                    add(expected, stampedOr(stamp, diskOperatorBucket()), size == null ? 0 : size);
                }
                case NIC_SLOT -> add(expected, stampedOr(stamp, InstanceDeviceQuota.nicBucketOf("")), 1);
                case NONE -> {
                    // shared operator media: charged nothing at create
                }
            }
        }
    }

    /**
     * Every bucket the reconciled dimensions could have charged: from ALL instance rows (a
     * trashed row names the bucket its own release landed in), from every host record, and
     * from every LEDGER row under a per-owner dimension's prefix.
     */
    private static @NonNull Set<String> candidateBuckets() {
        Set<String> buckets = new LinkedHashSet<>();
        for (Row row : Models.get(InstanceModel.class).find().all()) {
            String countBucket = InstanceQuota.chargedBucketOf(row);
            buckets.add(countBucket);
            buckets.add(InstanceQuota.memoryBucketOfChargedBucket(countBucket));
            Integer serverId = row.get(InstanceModel.SERVER_ID);
            if (serverId != null) {
                buckets.add(InstanceCapacity.bucketOf(serverId));
            }
        }
        for (Row server : Models.get(ServerModel.class).find().all()) {
            Integer serverId = server.get(ServerModel.ID);
            if (serverId != null) {
                buckets.add(InstanceCapacity.bucketOf(serverId));
            }
        }
        // The operator bucket of a dimension folds to exactly its prefix ("" packs to "").
        List<String> prefixes = List.of(SiteQuota.bucketKeyOf(""), DatabaseQuota.bucketKeyOf(""),
            PreviewQuota.bucketKeyOf(""), diskOperatorBucket(), InstanceDeviceQuota.nicBucketOf(""));
        for (Row ledger : Models.get(QuotaLedgerModel.class).find().all()) {
            String key = ledger.get(QuotaLedgerModel.BUCKET_KEY);
            if (key == null) {
                continue;
            }
            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    buckets.add(key);
                    break;
                }
            }
        }
        return buckets;
    }

    /** The disk dimension's operator bucket, shared by devices and root disks. */
    private static @NonNull String diskOperatorBucket() {
        return InstanceDeviceQuota.diskBucketOf("");
    }

    /**
     * The bucket a row's stamp names, else the dimension's operator bucket -- the fallback
     * every release path applies to a row stamped before its quota existed.
     */
    private static @NonNull String stampedOr(@Nullable String stamp, @NonNull String operatorBucket) {
        return stamp != null && !stamp.isBlank() ? stamp : operatorBucket;
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> settingsOf(@NonNull Row instance) {
        return instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
    }

    /** Declare a bucket as reachable, holding zero unless a row adds to it. */
    private static void candidate(@NonNull Map<String, Long> expected, @NonNull String bucket) {
        expected.putIfAbsent(bucket, 0L);
    }

    /**
     * What a live row holds against its OWNER's memory budget: its stamp, else the
     * footprint its settings imply (the release paths' own fallback, so a stamp-less row
     * reconciles to the number its eventual release will hand back).
     */
    private static long ownerMemoryOf(@NonNull Row row) {
        Integer stamped = row.get(InstanceModel.QUOTA_MEMORY_MB);
        return Math.max(0, stamped != null ? stamped : InstanceCapacity.footprintMbOf(row));
    }

    /** The same question for the HOST budget, asked through the booking's own home. */
    private static long hostMemoryOf(@NonNull Row row) {
        return InstanceCapacity.bookedMbOf(row);
    }

    private static void add(@NonNull Map<String, Long> expected, @Nullable String bucket,
                            long amount) {
        if (bucket != null) {
            expected.merge(bucket, amount, Long::sum);
        }
    }
}
