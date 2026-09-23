package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.quota.OwnerQuota;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.orm.quota.Quotas;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The disk-GB and extra-NIC quotas: the InstanceQuota shape over the same core
 * reservation ledger, charged ADJACENT to the device-row write. Disk rows reserve
 * their size in GB; NIC rows reserve one slot; a size update reserves/releases the
 * DELTA. Two racing attaches cannot both spend the last GB -- the ledger's guarded
 * statement is the enforcement, the create-form check is never it.
 *
 * The owner is the instance's manage-grant subject set as derived NOW (the restore-
 * transition idiom); unreadable grants fall back to the pack the instance's own
 * bucket was charged with. The charged bucket is stamped on the row and the release
 * always reads the STAMP, so counts stay exact when ownership drifts.
 *
 * AIDEV-NOTE: device rows are HARD-deleted only (detach, destroy cleanup), so the
 * remove-hook pairing is the one release lane -- there is no soft-delete transition
 * here, unlike InstanceQuota.
 */
public final class InstanceDeviceQuota {

    /** Consumer-namespaced bucket prefixes; the packed subject set follows. */
    static final String DISK_PREFIX = "hohenheim:disk_gb:";
    static final String NIC_PREFIX = "hohenheim:nics:";

    private static final String DOOMED = "hohenheim.device-quota.doomed";

    private static boolean installed;

    private InstanceDeviceQuota() {
    }

    /** The disk bucket for one packed subject set (191-char fold, the instance shape). */
    public static @NonNull String diskBucketOf(@NonNull String packedSubjects) {
        return fold(DISK_PREFIX, packedSubjects);
    }

    /** The NIC bucket for one packed subject set. */
    public static @NonNull String nicBucketOf(@NonNull String packedSubjects) {
        return fold(NIC_PREFIX, packedSubjects);
    }

    private static @NonNull String fold(@NonNull String prefix, @NonNull String packedSubjects) {
        return OwnerQuota.bucketOf(prefix, packedSubjects);
    }

    /** Install the reserve/release hooks on the device write funnel (MODULES stage). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        InstanceDeviceModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = storedOf(row);
            if (stored == null) {
                reserveCreate(row);
            } else {
                reserveDelta(row, stored);
            }
        });

        InstanceDeviceModel.SCHEMA.addBeforeRemoveHook(InstanceDeviceQuota::captureDoomed);
        InstanceDeviceModel.SCHEMA.addAfterRemoveHook(InstanceDeviceQuota::releaseDoomed);
    }

    // -- policy ---------------------------------------------------------------

    /**
     * The disk cap for one owner (GB): per-owner override else the global default;
     * override 0 = nothing allowed, global 0-or-less = uncapped (the max_instances
     * semantics exactly).
     */
    public static @Nullable Integer diskLimitFor(@NonNull String packedSubjects) {
        return OwnerQuota.limitOf(packedSubjects, InstanceQuotaModel.MAX_DISK_GB,
            HohenheimSettings.Quota.MAX_DISK_GB_PER_OWNER);
    }

    /** The extra-NIC cap for one owner; same override/default semantics. */
    public static @Nullable Integer nicLimitFor(@NonNull String packedSubjects) {
        return OwnerQuota.limitOf(packedSubjects, InstanceQuotaModel.MAX_NICS,
            HohenheimSettings.Quota.MAX_EXTRA_NICS_PER_OWNER);
    }

    // -- hook internals -------------------------------------------------------

    private static void reserveCreate(@NonNull Row row) {
        String pack = ownerPackOf(row.get(InstanceDeviceModel.INSTANCE_ID));
        // The charge is a FACT on the member, and a token that is no member is REFUSED
        // here: this branch used to charge nothing for anything it did not recognize.
        DeviceType type = DeviceType.require(row.get(InstanceDeviceModel.TYPE.getName()));
        String bucket = switch (type.charge()) {
            case DISK_GIGABYTES -> {
                Integer size = row.get(InstanceDeviceModel.SIZE_GB);
                long amount = size == null ? 0 : size;
                reserve(diskBucketOf(pack), amount, diskLimitFor(pack), "disk_quota_reached");
                yield diskBucketOf(pack);
            }
            case NIC_SLOT -> {
                reserve(nicBucketOf(pack), 1, nicLimitFor(pack), "nic_quota_reached");
                yield nicBucketOf(pack);
            }
            // A cdrom row charges NOTHING: it references shared operator media, allocates
            // no tenant storage, and is operator-attached by the funnel.
            case NONE -> null;
        };
        if (bucket != null) {
            row.set(InstanceDeviceModel.QUOTA_BUCKET, bucket);
        }
    }

    /** A size update charges/releases the DELTA against the STAMPED bucket. */
    private static void reserveDelta(@NonNull Row row, @NonNull Row stored) {
        if (!row.has(InstanceDeviceModel.SIZE_GB.getName())) {
            return;
        }
        DeviceType type = DeviceType.parse(stored.get(InstanceDeviceModel.TYPE));
        if (type == null || type.charge() != DeviceType.QuotaCharge.DISK_GIGABYTES) {
            return;
        }
        Integer before = stored.get(InstanceDeviceModel.SIZE_GB);
        Integer after = row.get(InstanceDeviceModel.SIZE_GB);
        long delta = (after == null ? 0 : after) - (before == null ? 0 : before);
        if (delta == 0) {
            return;
        }
        String bucket = chargedBucketOf(stored, type.charge());
        if (delta > 0) {
            String pack = packOf(bucket, DISK_PREFIX);
            reserve(bucket, delta, diskLimitFor(pack), "disk_quota_reached");
        } else {
            Quotas.release(bucket, -delta);
        }
    }

    private static void reserve(@NonNull String bucket, long amount, @Nullable Integer limit,
                                @NonNull String violationKey) {
        OwnerQuota.reserve(bucket, amount, limit, violationKey);
    }

    /**
     * The owner pack of one instance as derived NOW; unreadable grants fall back to
     * the pack behind the instance's own charged bucket (which may be a fold -- a
     * fold reserves correctly, it only cannot match an override row, exactly like the
     * instance bucket itself).
     */
    private static @NonNull String ownerPackOf(@Nullable Integer instanceId) {
        if (instanceId != null) {
            Set<String> subjects = HohenheimAccess.manageSubjectsOf(
                InstanceModel.MODEL_ID, instanceId);
            if (subjects != null) {
                return HohenheimAccess.packSubjects(subjects);
            }
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            String bucket = instance != null ? instance.get(InstanceModel.QUOTA_BUCKET) : null;
            if (bucket != null && bucket.startsWith(InstanceQuota.BUCKET_PREFIX)) {
                return bucket.substring(InstanceQuota.BUCKET_PREFIX.length());
            }
        }
        return "";
    }

    private static @NonNull String packOf(@NonNull String bucket, @NonNull String prefix) {
        return OwnerQuota.packOf(prefix, bucket);
    }

    /**
     * The packed subject set behind a disk bucket -- what {@link #diskLimitFor} wants.
     * Shared with {@link InstanceRootDiskQuota}, which charges the SAME bucket, so the
     * prefix stripping can never drift between the two halves of one cap.
     */
    static @NonNull String packOfDiskBucket(@NonNull String bucket) {
        return packOf(bucket, DISK_PREFIX);
    }

    /**
     * The bucket a stored row was charged to; a stampless row falls to the operator's
     * bucket of the SAME charge.
     */
    private static @NonNull String chargedBucketOf(@NonNull Row stored,
                                                   DeviceType.@NonNull QuotaCharge charge) {
        String bucket = stored.get(InstanceDeviceModel.QUOTA_BUCKET);
        if (bucket != null && !bucket.isBlank()) {
            return bucket;
        }
        Blast.log("QUOTA: device", stored.get(InstanceDeviceModel.ID),
            "carries no charged bucket; falling back to the operator bucket");
        return charge == DeviceType.QuotaCharge.DISK_GIGABYTES
            ? diskBucketOf("") : nicBucketOf("");
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(InstanceDeviceModel.ID.getName())
                || row.get(InstanceDeviceModel.ID) == null) {
            return null;
        }
        return Models.get(InstanceDeviceModel.class).findById(row.get(InstanceDeviceModel.ID));
    }

    /** One doomed row's release: the charged bucket and the charged amount. */
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
            DeviceType type = DeviceType.parse(row.get(InstanceDeviceModel.TYPE));
            if (type == null) {
                // No member charged it at create, so nothing is released: releasing a NIC
                // slot for it (the old else-branch) handed out capacity nobody had spent.
                Blast.log("QUOTA: device", row.get(InstanceDeviceModel.ID), "has unknown type",
                    row.get(InstanceDeviceModel.TYPE), "- nothing released on its removal");
                continue;
            }
            Integer size = row.get(InstanceDeviceModel.SIZE_GB);
            long amount = switch (type.charge()) {
                case DISK_GIGABYTES -> size == null ? 0 : size;
                case NIC_SLOT -> 1;
                case NONE -> 0;   // charged nothing at create, so releases nothing here
            };
            if (amount > 0) {
                doomed.add(new Doomed(chargedBucketOf(row, type.charge()), amount));
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
}
