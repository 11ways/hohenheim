package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
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
 * The per-owner SITE count quota: the InstanceQuota shape over its own bucket prefix,
 * charged ADJACENT to the site-row write.
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
 * AIDEV-NOTE: the RELEASE rides the deleted_at null -> non-null TRANSITION, because
 * SiteResource.deleteRow stamps deleted_at through save() and there is NO hard site delete
 * outside tests -- no remove hook would ever fire on the real path (the InstanceQuota
 * lesson verbatim). The remove pairing exists so a test's or a future bulk cleanup's hard
 * delete cannot leak a reservation either.
 *
 * Localization: the refusal is a Microcopy-backed violation; bucket keys are machine tokens.
 */
public final class SiteQuota {

    /** Consumer-namespaced bucket prefix; the packed subject set follows it. */
    static final String BUCKET_PREFIX = "hohenheim:sites:";

    private static final String DOOMED_BUCKETS = "hohenheim.site-quota.doomed-buckets";

    private static boolean installed;

    private SiteQuota() {
    }

    /** The site bucket for a packed subject set (the 191-char fold, one owner). */
    public static @NonNull String bucketKeyOf(@NonNull String packedSubjects) {
        return OwnerQuota.bucketOf(BUCKET_PREFIX, packedSubjects);
    }

    /** The site cap for one owner; override 0 = nothing allowed, global 0-or-less = uncapped. */
    public static @Nullable Integer limitFor(@NonNull String packedSubjects) {
        return OwnerQuota.limitOf(packedSubjects, InstanceQuotaModel.MAX_SITES,
            HohenheimSettings.Quota.MAX_SITES_PER_OWNER);
    }

    /** How many of an owner's site slots are spent (admin surfaces, tests). */
    public static long usedBy(@NonNull String packedSubjects) {
        return Quotas.usedOf(bucketKeyOf(packedSubjects));
    }

    /** Install the reserve/release hooks on the site write funnel (MODULES stage). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        SiteModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = storedOf(row);
            boolean storedLive = stored != null && stored.get(SiteModel.DELETED_AT) == null;
            boolean willBeLive = effectiveDeletedAt(row, stored) == null;

            if (stored == null) {
                if (willBeLive) {
                    reserveInto(row, HohenheimAccess.packSubjects(creationOwnerOf()));
                }
            } else if (storedLive && !willBeLive) {
                Quotas.release(chargedBucketOf(stored), 1);
            } else if (!storedLive && willBeLive) {
                // An untrash is a NEW claim on headroom, judged against the owner as
                // derived NOW (the record and its grants exist).
                Set<String> subjects =
                    HohenheimAccess.manageSubjectsOf(SiteModel.MODEL_ID, stored.get(SiteModel.ID));
                if (subjects == null) {
                    reserveIntoBucket(row, chargedBucketOf(stored));
                } else {
                    reserveInto(row, HohenheimAccess.packSubjects(subjects));
                }
            }
        });

        SiteModel.SCHEMA.addBeforeRemoveHook(SiteQuota::captureDoomedBuckets);
        SiteModel.SCHEMA.addAfterRemoveHook(SiteQuota::releaseDoomedBuckets);
    }

    // -- hook internals -------------------------------------------------------

    /** WHO a brand-new site is charged to: the creation owner of the acting context. */
    private static @NonNull Set<String> creationOwnerOf() {
        return HohenheimAccess.creationOwnerSubjects(
            TenantWrites.isTenantOriginated() ? TenantWrites.acting() : null);
    }

    private static void reserveInto(@NonNull Row row, @NonNull String packedSubjects) {
        reserveIntoBucket(row, bucketKeyOf(packedSubjects));
    }

    /**
     * Reserve one site slot and stamp the bucket -- usage is counted even when no cap is
     * configured, so enabling a cap later starts from honest numbers.
     */
    private static void reserveIntoBucket(@NonNull Row row, @NonNull String bucket) {
        OwnerQuota.reserve(bucket, 1, limitFor(OwnerQuota.packOf(BUCKET_PREFIX, bucket)),
            "site_quota_reached");
        row.set(SiteModel.QUOTA_BUCKET, bucket);
    }

    /** The bucket a stored row was charged to; pre-quota rows fall to the operator bucket. */
    private static @NonNull String chargedBucketOf(@NonNull Row stored) {
        String bucket = stored.get(SiteModel.QUOTA_BUCKET);
        if (bucket != null && !bucket.isBlank()) {
            return bucket;
        }
        Blast.log("QUOTA: site", stored.get(SiteModel.ID),
            "carries no charged bucket; releasing against the operator bucket");
        return bucketKeyOf("");
    }

    /** The value deleted_at will END UP with: staged when carried, else stored. */
    private static @Nullable Object effectiveDeletedAt(@NonNull Row row, @Nullable Row stored) {
        if (row.has(SiteModel.DELETED_AT.getName())) {
            return row.get(SiteModel.DELETED_AT.getName());
        }
        return stored != null ? stored.get(SiteModel.DELETED_AT) : null;
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(SiteModel.ID.getName()) || row.get(SiteModel.ID) == null) {
            return null;
        }
        return Models.get(SiteModel.class).findById(row.get(SiteModel.ID));
    }

    private static void captureDoomedBuckets(@NonNull RemoveFromDatasource context) {
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
        List<String> doomed = new ArrayList<>();
        for (Row row : builder.all()) {
            // Trashed rows already released on their soft-delete transition.
            if (row.get(SiteModel.DELETED_AT) == null) {
                doomed.add(chargedBucketOf(row));
            }
        }
        if (!doomed.isEmpty()) {
            context.setAttribute(DOOMED_BUCKETS, doomed);
        }
    }

    private static void releaseDoomedBuckets(@NonNull RemoveFromDatasource context) {
        if (!(context.getAttribute(DOOMED_BUCKETS) instanceof List<?> doomed)) {
            return;
        }
        for (Object bucket : doomed) {
            if (bucket instanceof String key) {
                Quotas.release(key, 1);
            }
        }
    }
}
