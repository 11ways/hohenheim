package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.quota.M001_CreateQuotaTable;
import be.elevenways.zenit.common.orm.quota.Quotas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-owner instance quotas: the override table, the charged-bucket column on
 * instances, and a heal that stamps every LIVE instance's bucket and seeds the core
 * ledger's used counts -- so a cap enabled after upgrade judges honest numbers
 * instead of starting every bucket at zero.
 *
 * AIDEV-NOTE: lives in the server source set because the owner derivation must be
 * the ONE authority (HohenheimAccess's manage-grant subject set); the grant rows are
 * read directly here because RecordGrants.listForRecord requires an initialized auth
 * layer that a migration-only run does not have -- the criteria below mirror it
 * verbatim (MODEL as Identifier.toString(), RECORD_ID stringified, truthy VALUE).
 * The bucket stamp uses updateAll ON PURPOSE: it is hook-free, so a heal running in
 * a JVM whose quota hook is already installed cannot double-reserve.
 *
 * @author Jelle De Loecker
 */
public class M055_InstanceQuotas extends HohenheimMigration {

    public M055_InstanceQuotas() {
        super("2026_08_03_130000", "Instance quotas");
        // The seed writes zenit_quotas; version order already agrees, this makes it structural.
        dependsOn(M001_CreateQuotaTable.class);
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("instance_quotas", table -> {
            table.id();
            // The packed manage-grant subject set ("" = the operator); one override per owner.
            table.addColumn("subjects", ColumnType.STRING,
                col -> col.maxLength(400).nullable(false));
            table.addColumn("max_instances", ColumnType.INTEGER, col -> col.nullable(true));
            table.timestamps();
            table.unique("instance_quotas_subjects_unique", List.of("subjects"));
        });
        schema.alterTable("instances", table -> table.addColumn("quota_bucket",
            ColumnType.STRING, col -> col.maxLength(191).nullable(true).ifNotExists()));
        schema.data("Stamp charged buckets and seed used counts for live instances", "1",
            M055_InstanceQuotas::backfill);
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("instance_quotas");
        schema.alterTable("instances", table -> table.dropColumn("quota_bucket"));
    }

    /** Derive each live instance's owner bucket, stamp it, and reserve it in the ledger. */
    static void backfill(Datasource datasource) {
        Db.run(datasource, () -> {
            InstanceModel instances = new InstanceModel();
            RecordGrantModel grants = new RecordGrantModel(datasource);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Row instance : instances.find()
                    .where(InstanceModel.DELETED_AT.isNull()).all()) {
                Integer id = instance.get(InstanceModel.ID);
                Set<String> subjects = new TreeSet<>();
                for (Row grant : grants.find()
                        .where(RecordGrantModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
                        .and(RecordGrantModel.RECORD_ID.eq(String.valueOf(id)))
                        .and(RecordGrantModel.CAPABILITY.eq(HohenheimAccess.MANAGE))
                        .all()) {
                    if (Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))) {
                        subjects.add(grant.get(RecordGrantModel.SUBJECT_TYPE)
                            + ":" + grant.get(RecordGrantModel.SUBJECT_ID));
                    }
                }
                String bucket = InstanceQuota.bucketKeyOf(HohenheimAccess.packSubjects(subjects));
                instances.find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.QUOTA_BUCKET, bucket)
                    .updateAll();
                counts.merge(bucket, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                // Uncapped on purpose: the heal records what EXISTS; whether it fits a cap
                // is the next create's question, never a boot failure.
                Quotas.reserve(entry.getKey(), entry.getValue(), Long.MAX_VALUE);
            }
        });
    }
}
