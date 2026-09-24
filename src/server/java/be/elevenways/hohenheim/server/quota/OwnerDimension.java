package be.elevenways.hohenheim.server.quota;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A dimension charged to an {@link OwnerBudget} whose bucket is STAMPED on the row: the
 * release always reads the stamp, because ownership moves through grants without touching
 * the row, and releasing against a re-derived owner is what drifts the ledger.
 *
 * AIDEV-NOTE: a stampless row (written before its dimension existed) is charged to the
 * budget's OPERATOR bucket, the one fallback every release path and the reconciler share.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public abstract class OwnerDimension implements ChargedDimension {

    private final @NonNull String key;
    protected final @NonNull OwnerBudget budget;
    private final @NonNull StringField bucketStamp;

    protected OwnerDimension(@NonNull String key, @NonNull OwnerBudget budget,
                             @NonNull StringField bucketStamp) {
        this.key = key;
        this.budget = budget;
        this.bucketStamp = bucketStamp;
    }

    @Override
    public @NonNull String key() {
        return this.key;
    }

    @Override
    public @NonNull String prefix() {
        return this.budget.prefix();
    }

    /** @return the amount a stored row holds, in the stamped bucket */
    protected abstract long heldAmount(@NonNull Row stored);

    @Override
    public @Nullable Charge held(@NonNull Row stored) {
        return this.heldIn(stored, this.heldAmount(stored), false);
    }

    /** The stamped bucket of a stored row with {@code amount} in it. */
    protected @NonNull Charge heldIn(@NonNull Row stored, long amount, boolean amountDerived) {
        String stamp = stored.get(this.bucketStamp);
        if (stamp != null && !stamp.isBlank()) {
            return new Charge(stamp, amount, amountDerived);
        }
        return new Charge(this.budget.operatorBucket(), amount, true);
    }

    /** {@code amount} in the bucket of a brand-new record's creation owner. */
    protected @NonNull Charge forCreationOwner(long amount) {
        return new Charge(this.budget.bucketOf(OwnerQuota.creationOwnerPack()), amount, false);
    }

    /**
     * {@code amount} in the bucket of a restored record's owner as derived NOW; unreadable
     * grants fail toward the bucket the row was charged to before.
     */
    protected @NonNull Charge forCurrentOwner(@NonNull Identifier model, @NonNull Row stored,
                                              @NonNull Object recordId, long amount) {
        String pack = OwnerQuota.currentOwnerPack(model, recordId);
        if (pack == null) {
            return new Charge(this.heldIn(stored, amount, false).bucket(), amount, false);
        }
        return new Charge(this.budget.bucketOf(pack), amount, false);
    }

    @Override
    public void reserve(@NonNull String bucket, long amount) {
        this.budget.reserve(bucket, amount);
    }

    /** Stamps the bucket, even for a zero charge, so a later change books against it. */
    @Override
    public void stamp(@NonNull Row row, @Nullable Charge charge) {
        if (charge != null) {
            row.set(this.bucketStamp, charge.bucket());
        }
    }
}
