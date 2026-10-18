package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.QuotaExceeded;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Set;

/**
 * The three answers every per-owner quota dimension needs, in ONE place: how a packed
 * subject set becomes a bucket key, how a bucket key becomes its pack again, and how the
 * override row / global default / uncapped triple resolves to a limit.
 *
 * AIDEV-NOTE: this exists because the 191-char sha256 FOLD was copied per dimension, and
 * the fold is a correctness surface, not a convenience: the charge and the release must
 * produce the SAME key for the same owner, and a per-dimension copy is how one of them
 * ends up folding on a different threshold and leaking a reservation nobody can release.
 * Folding is a one-way key derivation, so an override row is matched BY ITS BUCKET: the
 * owner it stores is folded through this same {@link #bucketOf} ({@link #overrideRowOf}).
 * It once matched the raw pack, which never equals a folded stamp, and with UUID owners
 * (about 42 characters each) a shared owner of five passes the fold, so its override
 * silently capped nothing.
 *
 * AIDEV-NOTE: ONE rule decides whether a pack folds ({@link #spelledPack}): past its own key ceiling, and wherever
 * the instance stamp folds it. Device and memory charges read their owner back from the instance's stamp (unreadable
 * grants), and a folded stamp names only a digest, so a budget that still spelled such a pack out charged it to a
 * bucket the owner's own charges never use: the per-key rule folded a 172-173 character pack under
 * {@code hohenheim:instances:} but not under the shorter {@code hohenheim:disk_gb:}. Only packs of 172 characters
 * and more spell differently than before (never four UUID subjects, at most 171); such a stamp keeps its spelling,
 * a release hands back the stamp it charged and the boot's QuotaReconciler rebuilds the ledger from the stamps.
 *
 * AIDEV-NOTE: 0-or-less means UNCAPPED for the global default and NOTHING ALLOWED for an
 * override row. That asymmetry is deliberate and predates this class: a global 0 is the
 * shipped "no policy yet" value, while a 0 someone typed into one owner's row is a
 * decision about that owner. Never "simplify" the two into one rule.
 *
 * Localization: bucket keys are machine tokens; only the refusal is localized content.
 */
public final class OwnerQuota {

    /** The ledger's primary-key ceiling. */
    private static final int MAX_KEY_LENGTH = 191;

    /** What a folded key carries after its prefix, ahead of the digest. */
    private static final String FOLD_MARK = "sha256:";

    private OwnerQuota() {
    }

    /** The bucket key of one packed subject set under {@code prefix}. */
    public static @NonNull String bucketOf(@NonNull String prefix, @NonNull String packedSubjects) {
        return prefix + spelledPack(prefix, packedSubjects);
    }

    /**
     * THE fold: how a pack is spelled inside a bucket key under {@code prefix}, itself while both that key and the
     * instance stamp spell it out, its digest beyond, so a pack the instance stamp folds folds under every budget.
     */
    private static @NonNull String spelledPack(@NonNull String prefix, @NonNull String packedSubjects) {
        int longest = MAX_KEY_LENGTH - Math.max(prefix.length(), OwnerBudget.INSTANCES.prefix().length());
        return packedSubjects.length() > longest
            ? FOLD_MARK + SecureTokens.sha256Hex(packedSubjects) : packedSubjects;
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
     * The cap for the owner behind one bucket in one dimension: the per-owner override column
     * when the owner's row carries a value (0 included -- nothing allowed), else the global
     * default, where 0 or less means no cap at all. A budget without an override column reads
     * the default only.
     *
     * @return the cap, or null for uncapped
     */
    public static @Nullable Integer limitOf(@NonNull String prefix, @NonNull String bucket,
                                            @Nullable IntegerField overrideColumn,
                                            @NonNull SettingDefinition<Integer> fallback) {
        Row override = overrideColumn == null ? null : overrideRowOf(prefix, bucket);
        Integer value = override != null ? override.get(overrideColumn) : null;
        if (value != null) {
            return value;
        }
        Integer global = Zenit.SETTINGS_VALUES.getValue(fallback);
        return global != null && global > 0 ? global : null;
    }

    /**
     * WHO a brand-new record is charged to: the creation owner of the acting write context,
     * packed -- the same derivation the creator's manage grant is planted from a moment later.
     */
    public static @NonNull String creationOwnerPack() {
        return HohenheimAccess.packSubjects(HohenheimAccess.creationOwnerSubjects(
            TenantWrites.isTenantOriginated() ? TenantWrites.acting() : null));
    }

    /**
     * The packed owner of an existing record as derived NOW from its manage grants.
     *
     * @return the pack, or null when the grants are unreadable (callers fall back or refuse)
     */
    public static @Nullable String currentOwnerPack(@NonNull Identifier model, @NonNull Object recordId) {
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(model, recordId);
        return subjects == null ? null : HohenheimAccess.packSubjects(subjects);
    }

    /**
     * The override row whose owner's bucket under {@code prefix} is {@code bucket}, a folded
     * bucket included.
     *
     * AIDEV-NOTE: a folded bucket names no pack, so every override owner is folded the same way
     * and compared; the override table holds one row per owner an operator capped by hand. An
     * unfolded bucket names its pack, which the unique owner index finds directly.
     *
     * @return the row, or null when the owner has none
     */
    public static @Nullable Row overrideRowOf(@NonNull String prefix, @NonNull String bucket) {
        Model overrides = Models.get(InstanceQuotaModel.class);
        if (!bucket.startsWith(prefix + FOLD_MARK)) {
            Row override = overrides.find()
                .where(InstanceQuotaModel.SUBJECTS.eq(packOf(prefix, bucket)))
                .first();
            return override != null && ownsBucket(override, prefix, bucket) ? override : null;
        }
        for (Row override : overrides.find().all()) {
            if (ownsBucket(override, prefix, bucket)) {
                return override;
            }
        }
        return null;
    }

    private static boolean ownsBucket(@NonNull Row override, @NonNull String prefix, @NonNull String bucket) {
        String subjects = override.get(InstanceQuotaModel.SUBJECTS);
        return subjects != null && bucketOf(prefix, subjects).equals(bucket);
    }

    /**
     * Spend {@code amount} of the bucket against {@code limit}, or refuse BY NAME with the
     * numbers in it. A non-positive amount books nothing: a declaration of nothing is
     * capped by nothing, so charging it would book what no gate enforces.
     *
     * @throws Violations the budget's violation key, carrying used and limit as its copy words them
     */
    public static void reserve(@NonNull String bucket, long amount, @Nullable Integer limit,
                               @NonNull OwnerBudget budget) {
        if (amount <= 0) {
            return;
        }
        try {
            Quotas.reserve(bucket, amount, limit == null ? Long.MAX_VALUE : limit);
        } catch (QuotaExceeded full) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of(budget.violationKey())
                .withArg("used", budget.worded(full.getUsed()))
                .withArg("limit", budget.worded(full.getLimit())));
        }
    }
}
