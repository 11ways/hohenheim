package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.instance.DeviceType;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.quota.ChargedDimension;
import be.elevenways.hohenheim.server.quota.ChargedModel;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.server.quota.OwnerDimension;
import be.elevenways.hohenheim.server.quota.OwnerQuota;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The disk-GB and extra-NIC quotas of attached devices, booked through
 * {@link ChargedModel#DEVICES}: a disk row reserves its size in GB, a NIC row one slot, and
 * a size update reserves or releases the DELTA. Two racing attaches cannot both spend the
 * last GB -- the ledger's guarded statement is the enforcement, the create-form check is
 * never it.
 *
 * The owner is the instance's manage-grant subject set as derived NOW (the restore-
 * transition idiom); unreadable grants fall back to the pack the instance's own bucket was
 * charged with. The charged bucket is stamped on the row and the release always reads the
 * STAMP, so counts stay exact when ownership drifts.
 *
 * AIDEV-NOTE: which dimension a row charges is a FACT on its type
 * ({@link DeviceType#charge()}): a disk charges {@link #DISK}, a NIC {@link #NICS}, a cdrom
 * nothing (it references shared operator media). A token that is no member is REFUSED at
 * create and releases nothing on removal -- nothing charged it.
 *
 * AIDEV-NOTE: device rows are HARD-deleted only (detach, destroy cleanup), so the remove
 * pairing is the one release lane -- there is no soft-delete transition here.
 */
public final class InstanceDeviceQuota {

    /** A disk device's size in the owner disk-GB bucket it shares with the root disk. */
    public static final ChargedDimension DISK =
        new DeviceDimension("device_disk", OwnerBudget.DISK_GB, DeviceType.QuotaCharge.DISK_GIGABYTES);

    /** One slot per extra NIC in the owner's NIC bucket. */
    public static final ChargedDimension NICS =
        new DeviceDimension("device_nics", OwnerBudget.NICS, DeviceType.QuotaCharge.NIC_SLOT);

    private InstanceDeviceQuota() {
    }

    /** The disk bucket for one packed subject set (191-char fold, the instance shape). */
    public static @NonNull String diskBucketOf(@NonNull String packedSubjects) {
        return OwnerBudget.DISK_GB.bucketOf(packedSubjects);
    }

    /** The NIC bucket for one packed subject set. */
    public static @NonNull String nicBucketOf(@NonNull String packedSubjects) {
        return OwnerBudget.NICS.bucketOf(packedSubjects);
    }

    /**
     * The disk cap for one owner (GB): per-owner override else the global default;
     * override 0 = nothing allowed, global 0-or-less = uncapped (the max_instances
     * semantics exactly).
     */
    public static @Nullable Integer diskLimitFor(@NonNull String packedSubjects) {
        return OwnerBudget.DISK_GB.limitFor(packedSubjects);
    }

    /** The extra-NIC cap for one owner; same override/default semantics. */
    public static @Nullable Integer nicLimitFor(@NonNull String packedSubjects) {
        return OwnerBudget.NICS.limitFor(packedSubjects);
    }

    /** The dimension of ONE device charge, holding only on rows whose type declares it. */
    private static final class DeviceDimension extends OwnerDimension {

        private final DeviceType.@NonNull QuotaCharge charge;

        DeviceDimension(@NonNull String key, @NonNull OwnerBudget budget,
                        DeviceType.@NonNull QuotaCharge charge) {
            super(key, budget, InstanceDeviceModel.QUOTA_BUCKET);
            this.charge = charge;
        }

        @Override
        protected long heldAmount(@NonNull Row stored) {
            return amountOf(stored);
        }

        @Override
        public @Nullable Charge held(@NonNull Row stored) {
            DeviceType type = DeviceType.parse(stored.get(InstanceDeviceModel.TYPE));
            return type != null && type.charge() == this.charge ? super.held(stored) : null;
        }

        @Override
        public @Nullable Charge claim(@NonNull Row row, @Nullable Row stored,
                                      @NonNull Transition transition) {
            return switch (transition) {
                case CREATE, RESTORE -> {
                    // A token that is no member is REFUSED here: this branch used to charge
                    // nothing for anything it did not recognize.
                    DeviceType type = DeviceType.require(row.get(InstanceDeviceModel.TYPE.getName()));
                    if (type.charge() != this.charge) {
                        yield null;
                    }
                    String pack = ownerPackOf(row.get(InstanceDeviceModel.INSTANCE_ID));
                    yield new Charge(this.budget.bucketOf(pack), amountOf(row), false);
                }
                case REBOOK -> {
                    Charge held = this.held(stored);
                    if (held == null || !row.has(InstanceDeviceModel.SIZE_GB.getName())) {
                        yield held;
                    }
                    // A size update charges or releases the DELTA against the STAMPED bucket.
                    yield new Charge(held.bucket(), amountOf(row), false);
                }
            };
        }

        /** What a row of this dimension's type is charged: its size in GB, or one NIC slot. */
        private long amountOf(@NonNull Row row) {
            return switch (this.charge) {
                case DISK_GIGABYTES -> {
                    Integer size = row.get(InstanceDeviceModel.SIZE_GB);
                    yield size == null ? 0 : size;
                }
                case NIC_SLOT -> 1;
                case NONE -> 0;
            };
        }
    }

    /**
     * The owner pack of one instance as derived NOW; unreadable grants fall back to the pack
     * behind the instance's own charged bucket (which may be a fold -- a fold reserves
     * correctly, it only cannot match an override row, exactly like the instance bucket).
     */
    private static @NonNull String ownerPackOf(@Nullable Integer instanceId) {
        if (instanceId != null) {
            String pack = OwnerQuota.currentOwnerPack(InstanceModel.MODEL_ID, instanceId);
            if (pack != null) {
                return pack;
            }
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            String bucket = instance != null ? instance.get(InstanceModel.QUOTA_BUCKET) : null;
            if (bucket != null && bucket.startsWith(OwnerBudget.INSTANCES.prefix())) {
                return OwnerBudget.INSTANCES.packOf(bucket);
            }
        }
        return "";
    }
}
