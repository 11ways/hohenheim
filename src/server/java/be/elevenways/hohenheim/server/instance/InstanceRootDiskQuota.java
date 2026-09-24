package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.quota.ChargedDimension;
import be.elevenways.hohenheim.server.quota.ChargedModel;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.server.quota.OwnerDimension;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The ROOT disk's charge, into the very same owner disk-GB bucket the attached
 * {@code instance_devices} rows use ({@link OwnerBudget#DISK_GB}), booked through
 * {@link ChargedModel#INSTANCES}. Without
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

    /**
     * The declared root GB of a live instance, in the owner disk bucket stamped as
     * {@code root_disk_bucket}; the stamp lands even for a zero charge, so a later grow books
     * against the bucket the row was born into.
     */
    public static final ChargedDimension ROOT_DISK =
        new OwnerDimension("root_disk", OwnerBudget.DISK_GB, InstanceModel.ROOT_DISK_BUCKET) {

            @Override
            protected long heldAmount(@NonNull Row stored) {
                return declaredGbOf(stored);
            }

            @Override
            public @Nullable Charge claim(@NonNull Row row, @Nullable Row stored,
                                          @NonNull Transition transition) {
                int amount = effectiveGb(row, stored);
                return switch (transition) {
                    // Charged to the owner the create is ABOUT TO HAVE -- the same derivation
                    // the manage grant that follows uses.
                    case CREATE -> this.forCreationOwner(amount);
                    case RESTORE -> this.forCurrentOwner(InstanceModel.MODEL_ID, stored,
                        stored.get(InstanceModel.ID), amount);
                    case REBOOK -> new Charge(this.held(stored).bucket(), amount, false);
                };
            }

            /**
             * Only a write that leaves the record LIVE is judged: a soft delete releases the
             * whole charge, so it is never refused for "shrinking" what it hands back.
             */
            @Override
            public void validate(@NonNull Row row, @Nullable Row stored) {
                requireDeclarable(row, stored);
            }
        };

    private InstanceRootDiskQuota() {
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

    // -- amounts -------------------------------------------------------------

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

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
