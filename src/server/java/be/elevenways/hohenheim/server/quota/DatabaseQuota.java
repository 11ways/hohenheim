package be.elevenways.hohenheim.server.quota;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceQuotaModel;
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
 * The per-owner MANAGED DATABASE count quota, charged ADJACENT to the database-row write.
 *
 * WHY it is not redundant with the instance quota, which a database already spends: the
 * engine container IS an instance and IS charged as one ({@code TenantDatabases.allocate}
 * -> {@code DatabaseInstances.reserveEngineRow}, attributed to the database's owner since
 * ce8ccb5). So a database costs one instance slot -- but an instance slot is a WORKLOAD
 * slot, and an owner spends those on game servers and stacks too, so "five databases per
 * tenant" is not expressible through it and never will be. A managed database is also a
 * different KIND of cost: credentials, a data volume, backups and a restore surface, none
 * of which the instance count knows about. The two dimensions are charged together and
 * both must fit; neither substitutes for the other.
 *
 * AIDEV-NOTE: databases have NO deleted_at (verified on DatabaseModel) -- every removal is
 * a HARD delete ({@code DatabaseService.destroy}, and {@code TenantDatabases.abandon}'s
 * criteria delete for a half-finished allocation). The remove pairing is therefore the ONE
 * release lane here, the mirror image of InstanceQuota, whose real lane is a soft-delete
 * transition. Get that backwards in either class and an owner is locked out one record at
 * a time.
 *
 * AIDEV-NOTE: the charge happens at the row INSERT, which in the tenant funnel is BEFORE
 * the creator's manage grant is planted -- so the owner is derived from the ACTING context
 * ({@code HohenheimAccess.creationOwnerSubjects}), which is the same answer
 * {@code grantCreatorManage} plants a moment later, deliberately one derivation.
 *
 * Localization: the refusal is a Microcopy-backed violation; bucket keys are machine tokens.
 */
public final class DatabaseQuota {

    /** Consumer-namespaced bucket prefix; the packed subject set follows it. */
    static final String BUCKET_PREFIX = "hohenheim:databases:";

    private static final String DOOMED_BUCKETS = "hohenheim.database-quota.doomed-buckets";

    private static boolean installed;

    private DatabaseQuota() {
    }

    /** The database bucket for a packed subject set (the 191-char fold, one owner). */
    public static @NonNull String bucketKeyOf(@NonNull String packedSubjects) {
        return OwnerQuota.bucketOf(BUCKET_PREFIX, packedSubjects);
    }

    /** The database cap for one owner; override 0 = nothing allowed, global 0-or-less = uncapped. */
    public static @Nullable Integer limitFor(@NonNull String packedSubjects) {
        return OwnerQuota.limitOf(packedSubjects, InstanceQuotaModel.MAX_DATABASES,
            HohenheimSettings.Quota.MAX_DATABASES_PER_OWNER);
    }

    /** How many of an owner's database slots are spent (admin surfaces, tests). */
    public static long usedBy(@NonNull String packedSubjects) {
        return Quotas.usedOf(bucketKeyOf(packedSubjects));
    }

    /** Install the reserve/release hooks on the database write funnel (MODULES stage). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        DatabaseModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null || storedOf(row) != null) {
                return;
            }
            String bucket = bucketKeyOf(HohenheimAccess.packSubjects(creationOwnerOf()));
            OwnerQuota.reserve(bucket, 1,
                limitFor(OwnerQuota.packOf(BUCKET_PREFIX, bucket)), "database_quota_reached");
            row.set(DatabaseModel.QUOTA_BUCKET, bucket);
        });

        DatabaseModel.SCHEMA.addBeforeRemoveHook(DatabaseQuota::captureDoomedBuckets);
        DatabaseModel.SCHEMA.addAfterRemoveHook(DatabaseQuota::releaseDoomedBuckets);
    }

    // -- hook internals -------------------------------------------------------

    /** WHO a brand-new database is charged to: the creation owner of the acting context. */
    private static @NonNull Set<String> creationOwnerOf() {
        return HohenheimAccess.creationOwnerSubjects(
            TenantWrites.isTenantOriginated() ? TenantWrites.acting() : null);
    }

    /** The bucket a stored row was charged to; pre-quota rows fall to the operator bucket. */
    private static @NonNull String chargedBucketOf(@NonNull Row stored) {
        String bucket = stored.get(DatabaseModel.QUOTA_BUCKET);
        if (bucket != null && !bucket.isBlank()) {
            return bucket;
        }
        Blast.log("QUOTA: database", stored.get(DatabaseModel.ID),
            "carries no charged bucket; releasing against the operator bucket");
        return bucketKeyOf("");
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(DatabaseModel.ID.getName()) || row.get(DatabaseModel.ID) == null) {
            return null;
        }
        return Models.get(DatabaseModel.class).findById(row.get(DatabaseModel.ID));
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
            doomed.add(chargedBucketOf(row));
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
