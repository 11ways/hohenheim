package be.elevenways.hohenheim.server.preview;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.quota.OwnerQuota;
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
import java.util.Set;

/**
 * Concurrent-preview quota per owner: the InstanceQuota shape over its own bucket
 * prefix. A preview's owner IS its site's grant-derived owner (a project is one
 * owner), so two racing webhook-triggered previews of one project contest one atomic
 * ledger row and the cap holds under concurrency. Unreadable grants fail CLOSED --
 * a preview whose owner cannot be derived is refused, never charged to nobody.
 */
public final class PreviewQuota {

    static final String BUCKET_PREFIX = "hohenheim:previews:";

    private static final String DOOMED_BUCKETS = "hohenheim.preview-quota.doomed-buckets";

    private static boolean installed;

    private PreviewQuota() {
    }

    public static @NonNull String bucketKeyOf(@NonNull String packedSubjects) {
        return OwnerQuota.bucketOf(BUCKET_PREFIX, packedSubjects);
    }

    /** @return the cap, or null for uncapped (0 or less in settings) */
    public static @Nullable Integer limit() {
        Integer cap = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Previews.MAX_PER_OWNER);
        return cap != null && cap > 0 ? cap : null;
    }

    public static long usedBy(@NonNull String packedSubjects) {
        return Quotas.usedOf(bucketKeyOf(packedSubjects));
    }

    /** Install the reserve/release hooks on the preview write funnel (MODULES stage). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        PreviewDeploymentModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = storedOf(row);
            boolean storedLive = stored != null
                && stored.get(PreviewDeploymentModel.DELETED_AT) == null;
            boolean willBeLive = effectiveDeletedAt(row, stored) == null;

            if (stored == null) {
                if (willBeLive) {
                    reserveInto(row, ownerPackOf(row));
                }
            } else if (storedLive && !willBeLive) {
                Quotas.release(chargedBucketOf(stored), 1);
            } else if (!storedLive && willBeLive) {
                // A restore is a new claim on headroom (unused today; correctness anyway).
                reserveInto(row, ownerPackOf(stored));
            }
        });

        PreviewDeploymentModel.SCHEMA.addBeforeRemoveHook(PreviewQuota::captureDoomedBuckets);
        PreviewDeploymentModel.SCHEMA.addAfterRemoveHook(PreviewQuota::releaseDoomedBuckets);
    }

    /** The owner pack of the preview's SITE; fails closed on unreadable grants. */
    private static @NonNull String ownerPackOf(@NonNull Row previewRow) {
        Object siteId = previewRow.has(PreviewDeploymentModel.SITE_ID.getName())
            ? previewRow.get(PreviewDeploymentModel.SITE_ID.getName()) : null;
        if (!(siteId instanceof Number number)) {
            throw Violations.ofField(PreviewDeploymentModel.SITE_ID.getName(), siteId,
                Microcopy.of("preview_site_required").withFilter("scope", "violations"));
        }
        Set<String> subjects = HohenheimAccess.manageSubjectsOf(
            SiteModel.MODEL_ID, number.intValue());
        if (subjects == null) {
            throw Violations.ofForm(Microcopy.of("preview_owner_unreadable")
                .withFilter("scope", "violations"));
        }
        return HohenheimAccess.packSubjects(subjects);
    }

    private static void reserveInto(@NonNull Row row, @NonNull String packedSubjects) {
        String bucket = bucketKeyOf(packedSubjects);
        Integer limit = limit();
        try {
            Quotas.reserve(bucket, 1, limit == null ? Long.MAX_VALUE : limit);
        } catch (QuotaExceeded full) {
            throw Violations.ofForm(Microcopy.of("preview_quota_reached")
                .withFilter("scope", "violations")
                .withArg("used", full.getUsed())
                .withArg("limit", full.getLimit()));
        }
        row.set(PreviewDeploymentModel.QUOTA_BUCKET, bucket);
    }

    private static @NonNull String chargedBucketOf(@NonNull Row stored) {
        String bucket = stored.get(PreviewDeploymentModel.QUOTA_BUCKET);
        if (bucket != null && !bucket.isBlank()) {
            return bucket;
        }
        Blast.log("QUOTA: preview", stored.get(PreviewDeploymentModel.ID),
            "carries no charged bucket; releasing against the operator bucket");
        return bucketKeyOf("");
    }

    private static @Nullable Object effectiveDeletedAt(@NonNull Row row, @Nullable Row stored) {
        if (row.has(PreviewDeploymentModel.DELETED_AT.getName())) {
            return row.get(PreviewDeploymentModel.DELETED_AT.getName());
        }
        return stored != null ? stored.get(PreviewDeploymentModel.DELETED_AT) : null;
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(PreviewDeploymentModel.ID.getName())
                || row.get(PreviewDeploymentModel.ID) == null) {
            return null;
        }
        return Models.get(PreviewDeploymentModel.class)
            .findById(row.get(PreviewDeploymentModel.ID));
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
            if (row.get(PreviewDeploymentModel.DELETED_AT) == null) {
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
