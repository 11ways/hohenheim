package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.quota.M001_CreateQuotaTable;
import be.elevenways.zenit.common.orm.quota.Quotas;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The per-OWNER memory budget: the cap column on the quota-override row, the booked-amount
 * column on instances, and a heal that prices every LIVE instance into its owner's bucket
 * -- so the first create after upgrade is judged against what owners ALREADY hold instead
 * of against an empty ledger.
 *
 * AIDEV-NOTE: the M080 shape deliberately, down to the uncapped seeding reserve. A heal
 * records what EXISTS; whether it fits a budget is the next create's question, never a
 * boot failure -- an owner already over their new cap must keep their workloads and be
 * refused the NEXT one.
 *
 * AIDEV-NOTE: the owner of an existing row is read from its STAMPED quota_bucket, not
 * re-derived from grants. The stamp is what the release will hand back to, so seeding any
 * other bucket would leave the heal and every future release disagreeing -- and a row that
 * predates the stamp column has no owner answer at all, which is why it seeds the operator
 * bucket (chargedBucketOf's own fallback) rather than being skipped.
 *
 * @author Jelle De Loecker
 */
public class M088_OwnerMemoryQuota extends HohenheimMigration {

    public M088_OwnerMemoryQuota() {
        super("2026_09_04_100000", "Per-owner memory quota");
        // The seed writes zenit_quotas; version order already agrees, this makes it structural.
        dependsOn(M001_CreateQuotaTable.class);
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instance_quotas", table -> table.addColumn("max_memory_mb",
            ColumnType.INTEGER, col -> col.nullable(true).ifNotExists()));
        schema.alterTable("instances", table -> table.addColumn("quota_memory_mb",
            ColumnType.INTEGER, col -> col.nullable(true).ifNotExists()));
        schema.data("Stamp booked owner memory and seed owner budgets for live instances", "1",
            M088_OwnerMemoryQuota::backfill);
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.dropColumn("quota_memory_mb"));
        schema.alterTable("instance_quotas", table -> table.dropColumn("max_memory_mb"));
    }

    /** Price each live instance, stamp the booking, and seed its OWNER's memory bucket. */
    static void backfill(Datasource datasource) {
        Db.run(datasource, () -> {
            InstanceModel instances = new InstanceModel();
            Map<String, Long> perOwner = new LinkedHashMap<>();
            for (Row instance : instances.find()
                    .where(InstanceModel.DELETED_AT.isNull()).all()) {
                int booked = InstanceCapacity.footprintMbOf(instance);
                Integer id = instance.get(InstanceModel.ID);
                // updateAll ON PURPOSE: it is hook-free, so a heal running in a JVM whose
                // quota hook is already installed cannot double-book.
                instances.find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.QUOTA_MEMORY_MB, booked)
                    .updateAll();
                if (booked > 0) {
                    perOwner.merge(memoryBucketOf(instance), (long) booked, Long::sum);
                }
            }
            for (Map.Entry<String, Long> entry : perOwner.entrySet()) {
                Quotas.reserve(entry.getKey(), entry.getValue(), Long.MAX_VALUE);
            }
        });
    }

    /** The owner memory bucket a stored row belongs to, per its stamped count bucket. */
    private static String memoryBucketOf(Row instance) {
        String bucket = instance.get(InstanceModel.QUOTA_BUCKET);
        if (bucket != null && !bucket.isBlank()) {
            return InstanceQuota.memoryBucketOfChargedBucket(bucket);
        }
        return InstanceQuota.memoryBucketOf(HohenheimAccess.packSubjects(Set.of()));
    }
}
