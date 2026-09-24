package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The per-owner SITE count quota: one slot per live site record, charged through
 * {@link ChargedModel#SITES}.
 *
 * WHY it cannot be the instance quota, which the site tier already touches: only a DOCKER
 * site lowers a container, and a container is an instance. Eight of the eleven site types
 * run no workload at all (proxy, redirect, static, TLS passthrough, ...), so counting
 * instances counts a minority of sites and "ten sites per tenant" is not expressible at
 * all. This dimension counts the RECORD, which is the thing an owner actually gets.
 *
 * AIDEV-NOTE: today the only lane that creates a site is the ADMIN panel --
 * {@code ManageSiteResource.creatable()} is false and the PaaS API has no site create --
 * so in practice this charges the operator bucket. That is not a reason to enforce it at a
 * surface instead: the gate lives on the WRITE FUNNEL precisely so the tenant lane that
 * arrives later inherits it without a second copy of the rule, and the derivation
 * ({@code HohenheimAccess.creationOwnerSubjects}) is already the one that lane will use.
 * The alternative -- a boolean on the future create surface -- is the check that cannot
 * fail under the concurrency a quota exists for.
 *
 * AIDEV-NOTE: the RELEASE rides the deleted_at null -> non-null TRANSITION, because a site
 * delete is SiteModel.SOFT_DELETE's, which stamps deleted_at through save(), and there is NO
 * hard site delete
 * outside tests -- no remove hook would ever fire on the real path (the InstanceQuota
 * lesson verbatim). The remove pairing exists so a test's or a future bulk cleanup's hard
 * delete cannot leak a reservation either.
 *
 * Localization: the refusal is a Microcopy-backed violation; bucket keys are machine tokens.
 */
public final class SiteQuota {

    /** One slot per live site; an untrash is a new claim against the owner derived now. */
    public static final ChargedDimension SITES =
        new OwnerDimension("sites", OwnerBudget.SITES, SiteModel.QUOTA_BUCKET) {

            @Override
            protected long heldAmount(@NonNull Row stored) {
                return 1;
            }

            @Override
            public @Nullable Charge claim(@NonNull Row row, @Nullable Row stored,
                                          @NonNull Transition transition) {
                return switch (transition) {
                    case CREATE -> this.forCreationOwner(1);
                    case RESTORE -> this.forCurrentOwner(SiteModel.MODEL_ID, stored,
                        stored.get(SiteModel.ID), 1);
                    case REBOOK -> this.held(stored);
                };
            }
        };

    private SiteQuota() {
    }

    /** The site bucket for a packed subject set (the 191-char fold, one owner). */
    public static @NonNull String bucketKeyOf(@NonNull String packedSubjects) {
        return OwnerBudget.SITES.bucketOf(packedSubjects);
    }

    /** The site cap for one owner; override 0 = nothing allowed, global 0-or-less = uncapped. */
    public static @Nullable Integer limitFor(@NonNull String packedSubjects) {
        return OwnerBudget.SITES.limitFor(packedSubjects);
    }

    /** How many of an owner's site slots are spent (admin surfaces, tests). */
    public static long usedBy(@NonNull String packedSubjects) {
        return OwnerBudget.SITES.usedBy(packedSubjects);
    }
}
