package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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
 * THE drift correction for the instance tier's three reservation buckets: what the LIVE
 * rows say each bucket holds is the truth, and the ledger is moved to it.
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
     * Recompute the owner-slot, owner-memory and host-memory buckets from the live instance
     * rows and correct every one that disagrees.
     *
     * AIDEV-NOTE: the DEVICE and ROOT-DISK buckets ({@code InstanceDeviceQuota},
     * {@code InstanceRootDiskQuota}) are deliberately NOT reconciled here. They are charged
     * from a different model's write and share one owner disk bucket across two of them, so
     * their truth is a different computation with its own failure modes -- and no drift has
     * been measured in them. Add them as their own scan when one is, never by widening this
     * one until it means "some buckets".
     */
    public static @NonNull Result reconcile() {
        Map<String, Long> expected = expectedBuckets();
        if (!expected.equals(expectedBuckets())) {
            Blast.log("QUOTA: reconcile abstained -- the instance rows moved under the scan;"
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
                "- the ledger disagreed with the live instance rows");
        }
        return new Result(corrections, false);
    }

    /**
     * What every reachable instance-tier bucket SHOULD hold: summed over live rows, and
     * explicitly zero for a bucket only trashed rows or hostless servers can name.
     */
    private static @NonNull Map<String, Long> expectedBuckets() {
        Map<String, Long> expected = new LinkedHashMap<>();
        for (String bucket : candidateBuckets()) {
            expected.put(bucket, 0L);
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
        }
        return expected;
    }

    /**
     * Every bucket this tier could have charged: from ALL instance rows (a trashed row
     * names the bucket its own release landed in) and from every host record.
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
        return buckets;
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

    /** The same question for the HOST budget, off the host-side stamp. */
    private static long hostMemoryOf(@NonNull Row row) {
        Integer stamped = row.get(InstanceModel.CAPACITY_MB);
        return Math.max(0, stamped != null ? stamped : InstanceCapacity.footprintMbOf(row));
    }

    private static void add(@NonNull Map<String, Long> expected, @Nullable String bucket,
                            long amount) {
        if (bucket != null) {
            expected.merge(bucket, amount, Long::sum);
        }
    }
}
