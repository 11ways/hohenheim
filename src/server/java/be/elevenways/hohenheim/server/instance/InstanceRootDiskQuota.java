package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.orm.quota.QuotaExceeded;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ROOT disk's charge, into the very same owner disk-GB bucket the attached
 * {@code instance_devices} rows use ({@link InstanceDeviceQuota#diskBucketOf}). Without
 * it {@code diskLimitFor} would ration only the disks an owner attaches and ignore the
 * one every workload already has, which is a hole in the cap, not a smaller cap.
 *
 * The doctrine is InstanceCapacity's, verbatim: charge equals cap. The GB charged here
 * is the number the driver hands the daemon as the root device's size, so a workload
 * physically cannot occupy more root storage than was charged for it.
 *
 * AIDEV-NOTE: no charged-AMOUNT stamp, unlike InstanceCapacity, and that is not an
 * omission. The amount IS the row's own {@code settings.root_disk_gb}, and every write
 * reconciles the delta between stored and staged, so the stored settings value equals
 * the outstanding charge by induction. The charged BUCKET does need a stamp
 * ({@code root_disk_bucket}): ownership moves through grants without touching the row,
 * and releasing against a re-derived owner is what drifts the ledger.
 *
 * AIDEV-NOTE: a kind that does not DECLARE the knob may not carry it. Declaring the
 * field is how a kind says its driver can enforce a root quota (see {@link RootDisk}),
 * so accepting the number on any other kind would charge an owner for a limit nothing
 * would apply -- the paper limit this whole capability exists to refuse.
 */
public final class InstanceRootDiskQuota {

    /** Where the before-remove hook stashes what the after-remove hook releases. */
    private static final String DOOMED = "hohenheim.root-disk-quota.doomed";

    private static boolean installed;

    private InstanceRootDiskQuota() {
    }

    /** Install the reserve/release hooks on the instance write funnel (MODULES stage). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        InstanceModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = storedOf(row);
            boolean storedLive = stored != null && stored.get(InstanceModel.DELETED_AT) == null;
            boolean willBeLive = effectiveDeletedAt(row, stored) == null;

            // Only a write that leaves the record LIVE has a declaration to judge: a
            // soft delete releases the whole charge, so it must never be refused for
            // "shrinking" the very thing it is handing back.
            if (willBeLive) {
                requireDeclarable(row, stored);
            }

            if (stored == null) {
                if (willBeLive) {
                    // Charged to the owner the create is ABOUT TO HAVE -- the same
                    // derivation the manage grant that follows uses (InstanceQuota).
                    reserveInto(row, effectiveGb(row, null), HohenheimAccess.packSubjects(
                        HohenheimAccess.creationOwnerSubjects(
                            TenantWrites.isTenantOriginated() ? TenantWrites.acting() : null)));
                }
            } else if (storedLive && !willBeLive) {
                releaseStored(stored);
            } else if (!storedLive && willBeLive) {
                // The restore transition: a new claim on headroom, judged against the
                // owner as derived NOW; unreadable grants fail toward the charged bucket.
                Set<String> subjects = HohenheimAccess.manageSubjectsOf(
                    InstanceModel.MODEL_ID, stored.get(InstanceModel.ID));
                if (subjects == null) {
                    reserveIntoBucket(row, effectiveGb(row, stored), chargedBucketOf(stored));
                } else {
                    reserveInto(row, effectiveGb(row, stored),
                        HohenheimAccess.packSubjects(subjects));
                }
            } else if (storedLive) {
                rebook(row, stored);
            }
        });

        InstanceModel.SCHEMA.addBeforeRemoveHook(InstanceRootDiskQuota::captureDoomed);
        InstanceModel.SCHEMA.addAfterRemoveHook(InstanceRootDiskQuota::releaseDoomed);
    }

    // -- declaration validity -------------------------------------------------

    /**
     * Refuse a root-disk declaration that is unusable or that this kind's driver could
     * not enforce -- BY NAME, at the surface the operator submitted it on.
     */
    private static void requireDeclarable(@NonNull Row row, @Nullable Row stored) {
        if (!row.has(InstanceModel.SETTINGS.getName())) {
            return;
        }
        Object raw = settingsOf(row).get(RootDisk.SETTING);
        if (raw == null || (raw instanceof String text && text.isBlank())) {
            // No declaration in this write. That is not automatically "nothing to
            // check": on a row that ALREADY has one, an absent or blank key is a
            // CLEAR, and a clear frees no storage (see requireNoShrink).
            requireNoShrink(row, stored);
            return;
        }
        if (RootDisk.declaredGb(settingsOf(row)) == null) {
            throw Violations.ofField(RootDisk.SETTING, raw,
                violation("root_disk_invalid"));
        }
        String kind = effectiveKind(row, stored);
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler == null || handler.getSchema() == null
                || handler.getSchema().getField(RootDisk.SETTING) == null) {
            throw Violations.ofField(RootDisk.SETTING, raw,
                violation("root_disk_unsupported").withArg("kind", String.valueOf(kind)));
        }
        requireNoShrink(row, stored);
    }

    /**
     * A root disk can only ever grow, so the DECLARATION can only ever grow.
     *
     * AIDEV-NOTE: enforced at the WRITE and not only at deploy, and the reason is the
     * ledger. A record allowed to declare less than the daemon already gave it would
     * release the difference while the storage stays occupied -- the owner's disk cap
     * would then permit more than the host can hold, which is precisely the hole this
     * charge exists to close. Clearing the field counts as a shrink for the same reason:
     * the volume does not go back to the image default just because the field is blank.
     */
    private static void requireNoShrink(@NonNull Row row, @Nullable Row stored) {
        if (stored == null) {
            return;
        }
        int before = declaredGbOf(stored);
        if (before <= 0) {
            return;
        }
        int after = effectiveGb(row, stored);
        if (after < before) {
            throw Violations.ofField(RootDisk.SETTING, after,
                violation("root_disk_shrink").withArg("current", before));
        }
    }

    // -- hook internals -------------------------------------------------------

    private static void reserveInto(@NonNull Row row, int amount, @NonNull String packed) {
        reserveIntoBucket(row, amount, InstanceDeviceQuota.diskBucketOf(packed));
    }

    /**
     * Reserve the GB and stamp the bucket -- the stamp lands even for a zero charge, so a
     * later grow releases and re-reserves against the bucket the row was born into.
     */
    private static void reserveIntoBucket(@NonNull Row row, int amount, @NonNull String bucket) {
        reserve(bucket, amount);
        row.set(InstanceModel.ROOT_DISK_BUCKET, bucket);
    }

    private static void reserve(@NonNull String bucket, long amount) {
        if (amount <= 0) {
            return;
        }
        String packed = InstanceDeviceQuota.packOfDiskBucket(bucket);
        Integer limit = InstanceDeviceQuota.diskLimitFor(packed);
        try {
            Quotas.reserve(bucket, amount, limit == null ? Long.MAX_VALUE : limit);
        } catch (QuotaExceeded full) {
            throw Violations.ofForm(violation("disk_quota_reached")
                .withArg("used", full.getUsed())
                .withArg("limit", full.getLimit()));
        }
    }

    /** A live row staying live: charge or release the DELTA against the stamped bucket. */
    private static void rebook(@NonNull Row row, @NonNull Row stored) {
        int before = declaredGbOf(stored);
        int after = effectiveGb(row, stored);
        if (before == after) {
            return;
        }
        String bucket = chargedBucketOf(stored);
        if (after > before) {
            reserve(bucket, after - before);
        } else {
            Quotas.release(bucket, before - after);
        }
        row.set(InstanceModel.ROOT_DISK_BUCKET, bucket);
    }

    private static void releaseStored(@NonNull Row stored) {
        int amount = declaredGbOf(stored);
        if (amount > 0) {
            Quotas.release(chargedBucketOf(stored), amount);
        }
    }

    /** The bucket a stored row was charged to; a stampless row falls to the operator's. */
    private static @NonNull String chargedBucketOf(@NonNull Row stored) {
        String bucket = stored.get(InstanceModel.ROOT_DISK_BUCKET);
        if (bucket != null && !bucket.isBlank()) {
            return bucket;
        }
        Blast.log("QUOTA: instance", stored.get(InstanceModel.ID),
            "carries no charged root-disk bucket; falling back to the operator bucket");
        return InstanceDeviceQuota.diskBucketOf("");
    }

    /** The declared root GB of a stored row (0 = none declared). */
    private static int declaredGbOf(@NonNull Row instance) {
        Integer declared = RootDisk.declaredGb(settingsOf(instance));
        return declared == null ? 0 : declared;
    }

    /**
     * The GB the write will END UP charged: a partial CMS update carries only the changed
     * keys (the SiteDomainModel.effective idiom), so pricing the staged row alone would
     * read a settings-less edit as a cleared root disk and hand the charge back.
     */
    private static int effectiveGb(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.SETTINGS.getName()) || stored == null) {
            return declaredGbOf(row);
        }
        return declaredGbOf(stored);
    }

    private static @Nullable String effectiveKind(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.KIND.getName()) || stored == null) {
            return row.get(InstanceModel.KIND);
        }
        return stored.get(InstanceModel.KIND);
    }

    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> settingsOf(@NonNull Row instance) {
        return instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
    }

    private static @Nullable Object effectiveDeletedAt(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.DELETED_AT.getName())) {
            return row.get(InstanceModel.DELETED_AT.getName());
        }
        return stored != null ? stored.get(InstanceModel.DELETED_AT) : null;
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(InstanceModel.ID.getName()) || row.get(InstanceModel.ID) == null) {
            return null;
        }
        return Models.get(InstanceModel.class).findById(row.get(InstanceModel.ID));
    }

    /** One doomed row's release: the charged bucket and the charged GB. */
    private record Doomed(@NonNull String bucket, long amount) {}

    private static void captureDoomed(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return;
        }
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        QueryBuilder<Row> builder = model.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        List<Doomed> doomed = new ArrayList<>();
        for (Row row : builder.all()) {
            // Trashed rows already released on their soft-delete transition.
            if (row.get(InstanceModel.DELETED_AT) != null) {
                continue;
            }
            long amount = declaredGbOf(row);
            if (amount > 0) {
                doomed.add(new Doomed(chargedBucketOf(row), amount));
            }
        }
        if (!doomed.isEmpty()) {
            context.setAttribute(DOOMED, doomed);
        }
    }

    private static void releaseDoomed(@NonNull RemoveFromDatasource context) {
        if (!(context.getAttribute(DOOMED) instanceof List<?> doomed)) {
            return;
        }
        for (Object entry : doomed) {
            if (entry instanceof Doomed release) {
                Quotas.release(release.bucket(), release.amount());
            }
        }
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
