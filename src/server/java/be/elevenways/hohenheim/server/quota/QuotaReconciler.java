package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.server.quota.ChargedDimension.Charge;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.QuotaLedgerModel;
import be.elevenways.zenit.common.orm.quota.Quotas;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE drift correction for every quota dimension {@link ChargedModel} declares -- the
 * instance tier's slot, owner-memory, host-memory and root-disk buckets, and the device,
 * preview, site and database buckets: what the live rows say each bucket holds is the truth,
 * and the ledger is moved to it.
 *
 * AIDEV-NOTE: this class names NO dimension. Truth is every declared dimension's
 * {@link ChargedDimension#reconciled} over its model's live rows -- the very charges the
 * release paths hand back -- and the candidates are every ledger row under a declared
 * dimension's prefix, so a leaked bucket no row names (an owner whose only create failed, a
 * host that is gone) is corrected to zero. A new dimension is reconciled by being declared.
 *
 * AIDEV-NOTE: widened from the instance tier alone on 2026-09-23. A managed-database slot
 * leaked forever when two concurrent creates of one name both reserved and the loser's
 * insert failed (DatabaseService.insertRecord now runs in one transaction, so that shape
 * rolls back), and nothing could ever repair a leak in any dimension but the instance
 * one. Each dimension's truth is computed from the very stamps its release path hands back
 * ({@code quota_bucket}, {@code root_disk_bucket}, a stampless row falling to the operator
 * bucket of the same dimension exactly as the releases do), so a correction can never
 * disagree with what a later delete will release.
 *
 * AIDEV-NOTE: this exists because the charge hook cannot see a refusal that comes AFTER it.
 * Since 2026-09-24 the dimensions of one model share ONE hook ({@link ChargedModel}) that
 * unwinds its own moves when a later dimension refuses -- so the robbedoes shape of
 * 2026-09-01 ({@code owner_mem_mb} exactly 512 MB above the live bookings and
 * {@code instances} 15 against 14 live rows: an owner-side spend followed by a host-side
 * {@code host_capacity_reached}) no longer arises. What remains is a refusal after the hook
 * (a later hook, the insert itself) on a write with no ambient transaction -- an instance
 * save opens none -- plus the drift already sitting in a deployed ledger. This lane covers
 * both, and every future shape of them.
 *
 * TRUTH, precisely: a bucket's expected value is the sum of the declared dimensions'
 * reconciled charges over LIVE rows only; a live row mid-migration also accounts for its
 * open window's booking on the destination host ({@code migrate_reserved_mb}). Candidates
 * are every ledger row under a declared prefix, so an owner whose last record is gone is
 * corrected to zero rather than left holding a leak no live row could name.
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
        Set<String> prefixes = ChargedModel.prefixes();
        for (Row ledger : Models.get(QuotaLedgerModel.class).find().all()) {
            String key = ledger.get(QuotaLedgerModel.BUCKET_KEY);
            if (key == null) {
                continue;
            }
            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    expected.putIfAbsent(key, 0L);
                    break;
                }
            }
        }
        for (ChargedModel model : ChargedModel.values()) {
            for (Row row : model.liveRows()) {
                for (ChargedDimension dimension : model.dimensions()) {
                    for (Charge charge : dimension.reconciled(row)) {
                        expected.merge(charge.bucket(), charge.amount(), Long::sum);
                    }
                }
            }
        }
        return expected;
    }
}
