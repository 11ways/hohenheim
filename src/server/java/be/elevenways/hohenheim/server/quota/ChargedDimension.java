package be.elevenways.hohenheim.server.quota;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * One quantity a row of a {@link ChargedModel} holds in the quota ledger: what a stored row
 * holds, what a write will hold, how a bucket is spent and where the charge is stamped.
 *
 * AIDEV-NOTE: a dimension answers QUESTIONS and never touches the ledger's lifecycle itself.
 * The transitions (create reserves, live to trashed releases, trashed to live reserves
 * against the owner derived now, live to live rebooks the difference, a hard delete releases
 * after the rows are gone) and the unwind of a refused write are {@link ChargedModel}'s, once,
 * for every dimension. A dimension that wants a different lifecycle is a design question,
 * not an override.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public interface ChargedDimension {

    /**
     * What a row holds in one dimension.
     *
     * @param bucket  the ledger bucket the amount is booked in
     * @param amount  the booked amount; 0 books nothing but still names the bucket
     * @param derived true when the bucket or the amount fell back from a missing stamp, which a
     *                release reports because it may hand back a number that was never booked
     */
    record Charge(@NonNull String bucket, long amount, boolean derived) {

        /** @return whether both charges book the same amount in the same bucket */
        public boolean sameBooking(@Nullable Charge other) {
            return other != null && this.bucket.equals(other.bucket) && this.amount == other.amount;
        }
    }

    /** Which claim a write makes; a release is never a claim. */
    enum Transition {
        /** A row that did not exist, written live. */
        CREATE,
        /** A trashed row written live again: a new claim on headroom. */
        RESTORE,
        /** A live row that stays live: only the difference moves. */
        REBOOK
    }

    /** @return the stable name of this dimension (logs, the drift test) */
    @NonNull String key();

    /** @return the ledger prefix every bucket this dimension books starts with */
    @NonNull String prefix();

    /**
     * What a stored row holds, read off its stamps with the release paths' own fallback.
     *
     * @return the charge, or null when the row holds nothing in this dimension
     */
    @Nullable Charge held(@NonNull Row stored);

    /**
     * What the write will hold once it lands. A REBOOK keeps the held bucket unless the
     * dimension's bucket follows a column the write changes (a host).
     *
     * @param stored null on CREATE
     * @return the charge, or null when the written row holds nothing in this dimension
     * @throws Violations when the claim itself is refused
     */
    @Nullable Charge claim(@NonNull Row row, @Nullable Row stored, @NonNull Transition transition);

    /**
     * Spend a positive {@code amount} of {@code bucket} against this dimension's cap.
     *
     * @throws Violations naming the cap that refused
     */
    void reserve(@NonNull String bucket, long amount);

    /** Record on the row being written what it now holds; null is the "holds nothing" state. */
    void stamp(@NonNull Row row, @Nullable Charge charge);

    /**
     * Refuse a declaration this dimension cannot charge, on a write that leaves the row live.
     *
     * @throws Violations naming the unusable declaration
     */
    default void validate(@NonNull Row row, @Nullable Row stored) {
    }

    /**
     * Every booking a LIVE row accounts for when the reconciler recomputes the ledger:
     * {@link #held} unless a dimension books more than a release would hand back.
     */
    default @NonNull List<Charge> reconciled(@NonNull Row live) {
        Charge held = this.held(live);
        return held == null ? List.of() : List.of(held);
    }
}
