package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.quota.DatabaseQuota;
import be.elevenways.hohenheim.server.quota.SiteQuota;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.quota.M001_CreateQuotaTable;
import be.elevenways.zenit.common.orm.quota.Quotas;

/**
 * Per-owner SITE and managed-DATABASE count quotas: the two cap columns on the quota
 * override row, the charged-bucket stamp on each counted record, and a heal that seeds the
 * existing population into the operator bucket -- so the first create after upgrade is
 * judged against what exists instead of against an empty ledger.
 *
 * AIDEV-NOTE: everything that exists TODAY is seeded to the operator bucket, and that is
 * correct rather than lazy: neither table has ever carried a charged bucket, so there is no
 * per-record owner answer to recover, and the only lane that has ever created a site is the
 * admin panel (whose creation owner IS the operator). A tenant-held record's future release
 * reads the stamp this heal writes, so the seed and the release agree by construction.
 *
 * AIDEV-NOTE: the M055/M080 shape, uncapped seeding reserve included. A heal records what
 * EXISTS; whether it fits a cap is the next create's question, never a boot failure.
 *
 * @author Jelle De Loecker
 */
public class M089_SiteAndDatabaseQuotas extends HohenheimMigration {

    public M089_SiteAndDatabaseQuotas() {
        super("2026_09_05_100000", "Site and database quotas");
        // The seed writes zenit_quotas; version order already agrees, this makes it structural.
        dependsOn(M001_CreateQuotaTable.class);
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instance_quotas", table -> table.addColumn("max_sites",
            ColumnType.INTEGER, col -> col.nullable(true).ifNotExists()));
        schema.alterTable("instance_quotas", table -> table.addColumn("max_databases",
            ColumnType.INTEGER, col -> col.nullable(true).ifNotExists()));
        schema.alterTable("sites", table -> table.addColumn("quota_bucket",
            ColumnType.STRING, col -> col.maxLength(191).nullable(true).ifNotExists()));
        schema.alterTable("managed_databases", table -> table.addColumn("quota_bucket",
            ColumnType.STRING, col -> col.maxLength(191).nullable(true).ifNotExists()));
        schema.data("Stamp and seed the existing site and database populations", "1",
            M089_SiteAndDatabaseQuotas::backfill);
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("managed_databases", table -> table.dropColumn("quota_bucket"));
        schema.alterTable("sites", table -> table.dropColumn("quota_bucket"));
        schema.alterTable("instance_quotas", table -> table.dropColumn("max_databases"));
        schema.alterTable("instance_quotas", table -> table.dropColumn("max_sites"));
    }

    /** Stamp every live site and every database with the operator bucket and seed it. */
    static void backfill(Datasource datasource) {
        Db.run(datasource, () -> {
            String siteBucket = SiteQuota.bucketKeyOf("");
            SiteModel sites = new SiteModel();
            long liveSites = 0;
            for (Row site : sites.find().where(SiteModel.DELETED_AT.isNull()).all()) {
                // updateAll ON PURPOSE: it is hook-free, so a heal running in a JVM whose
                // quota hook is already installed cannot double-book.
                sites.find().where(SiteModel.ID.eq(site.get(SiteModel.ID)))
                    .assign(SiteModel.QUOTA_BUCKET, siteBucket)
                    .bypassBehaviours()
                    .updateAll();
                liveSites++;
            }
            if (liveSites > 0) {
                Quotas.reserve(siteBucket, liveSites, Long.MAX_VALUE);
            }

            String databaseBucket = DatabaseQuota.bucketKeyOf("");
            DatabaseModel databases = new DatabaseModel();
            long count = 0;
            for (Row database : databases.find().all()) {
                databases.find().where(DatabaseModel.ID.eq(database.get(DatabaseModel.ID)))
                    .assign(DatabaseModel.QUOTA_BUCKET, databaseBucket)
                    .updateAll();
                count++;
            }
            if (count > 0) {
                Quotas.reserve(databaseBucket, count, Long.MAX_VALUE);
            }
        });
    }
}
