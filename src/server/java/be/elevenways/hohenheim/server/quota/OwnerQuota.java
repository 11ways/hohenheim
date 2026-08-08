package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.QuotaExceeded;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The three answers every per-owner quota dimension needs, in ONE place: how a packed
 * subject set becomes a bucket key, how a bucket key becomes its pack again, and how the
 * override row / global default / uncapped triple resolves to a limit.
 *
 * AIDEV-NOTE: this exists because the 191-char sha256 FOLD was copied per dimension, and
 * the fold is a correctness surface, not a convenience: the charge and the release must
 * produce the SAME key for the same owner, and a per-dimension copy is how one of them
 * ends up folding on a different threshold and leaking a reservation nobody can release.
 * Folding is a one-way key derivation, so a folded owner reserves correctly but can no
 * longer MATCH an override row -- that limitation is inherent (the override table stores
 * the readable pack) and is why {@link #limitOf} takes the pack, not the bucket.
 *
 * AIDEV-NOTE: 0-or-less means UNCAPPED for the global default and NOTHING ALLOWED for an
 * override row. That asymmetry is deliberate and predates this class: a global 0 is the
 * shipped "no policy yet" value, while a 0 someone typed into one owner's row is a
 * decision about that owner. Never "simplify" the two into one rule.
 *
 * Localization: bucket keys are machine tokens; only the refusal is localized content.
 */
public final class OwnerQuota {

    /** The ledger's primary-key ceiling; a longer key folds through a digest. */
    private static final int MAX_KEY_LENGTH = 191;

    private OwnerQuota() {
    }

    /** The bucket key of one packed subject set under {@code prefix}. */
    public static @NonNull String bucketOf(@NonNull String prefix, @NonNull String packedSubjects) {
        String key = prefix + packedSubjects;
        if (key.length() > MAX_KEY_LENGTH) {
            key = prefix + "sha256:" + SecureTokens.sha256Hex(packedSubjects);
        }
        return key;
    }

    /**
     * The packed subject set behind a bucket key -- the inverse of {@link #bucketOf} for
     * every unfolded key, and the digest spelling for a folded one (which re-folds to the
     * same key, so a delta charge still lands in the right bucket).
     */
    public static @NonNull String packOf(@NonNull String prefix, @NonNull String bucket) {
        return bucket.startsWith(prefix) ? bucket.substring(prefix.length()) : bucket;
    }

    /**
     * The cap for one owner in one dimension: the per-owner override column when the row
     * carries a value (0 included -- nothing allowed), else the global default, where 0 or
     * less means no cap at all.
     *
     * @return the cap, or null for uncapped
     */
    public static @Nullable Integer limitOf(@NonNull String packedSubjects,
                                            @NonNull IntegerField overrideColumn,
                                            @NonNull SettingDefinition<Integer> fallback) {
        Integer override = overrideOf(packedSubjects, overrideColumn);
        if (override != null) {
            return override;
        }
        Integer value = HohenheimSettings.VALUES.getValue(fallback);
        return value != null && value > 0 ? value : null;
    }

    /** The override value of one column for one owner, or null when there is no row/value. */
    public static @Nullable Integer overrideOf(@NonNull String packedSubjects,
                                               @NonNull IntegerField overrideColumn) {
        Row override = Models.get(InstanceQuotaModel.class).find()
            .where(InstanceQuotaModel.SUBJECTS.eq(packedSubjects))
            .first();
        return override != null ? override.get(overrideColumn) : null;
    }

    /**
     * Spend {@code amount} of the bucket against {@code limit}, or refuse BY NAME with the
     * numbers in it. A non-positive amount books nothing: the ProcessCapacity.reserve
     * precedent -- a declaration of nothing is capped by nothing, so charging it would
     * book what no gate enforces.
     *
     * @throws Violations {@code violationKey}, carrying used and limit
     */
    public static void reserve(@NonNull String bucket, long amount, @Nullable Integer limit,
                               @NonNull String violationKey) {
        if (amount <= 0) {
            return;
        }
        try {
            Quotas.reserve(bucket, amount, limit == null ? Long.MAX_VALUE : limit);
        } catch (QuotaExceeded full) {
            throw Violations.ofForm(Microcopy.of(violationKey)
                .withFilter("scope", "violations")
                .withArg("used", full.getUsed())
                .withArg("limit", full.getLimit()));
        }
    }
}
