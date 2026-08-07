package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import be.elevenways.zenit.common.orm.quota.M001_CreateQuotaTable;
import be.elevenways.zenit.common.orm.quota.Quotas;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-HOST memory capacity: the booked-amount column on instances, and a heal that prices
 * every LIVE instance and seeds its host's bucket -- so the first create after upgrade is
 * judged against what the hosts are ALREADY carrying instead of against an empty ledger.
 *
 * AIDEV-NOTE: the M055 shape deliberately, down to the uncapped seeding reserve. A heal
 * records what EXISTS; whether it fits a budget is the next create's question, never a
 * boot failure -- a host that is already overbooked must keep running its workloads and
 * refuse the NEXT one.
 *
 * AIDEV-NOTE: the pricing goes through InstanceCapacity.footprintMbOf, which resolves the
 * kind handler, so a row whose kind class no longer exists prices at 0 and books nothing.
 * That is the same answer the runtime gives such a row (it cannot be deployed either),
 * and it is why this is not a second footprint rule living in a migration.
 *
 * @author Jelle De Loecker
 */
public class M080_InstanceCapacity extends HohenheimMigration {

    public M080_InstanceCapacity() {
        super("2026_08_27_100000", "Instance host capacity");
        // The seed writes zenit_quotas; version order already agrees, this makes it structural.
        dependsOn(M001_CreateQuotaTable.class);
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.addColumn("capacity_mb",
            ColumnType.INTEGER, col -> col.nullable(true).ifNotExists()));
        schema.data("Stamp booked memory and seed host capacity for live instances", "1",
            M080_InstanceCapacity::backfill);
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.dropColumn("capacity_mb"));
    }

    /** Price each live instance, stamp the booking, and seed its host's bucket. */
    static void backfill(Datasource datasource) {
        Db.run(datasource, () -> {
            InstanceModel instances = new InstanceModel();
            Map<Integer, Long> perHost = new LinkedHashMap<>();
            for (Row instance : instances.find()
                    .where(InstanceModel.DELETED_AT.isNull()).all()) {
                int booked = InstanceCapacity.footprintMbOf(instance);
                Integer id = instance.get(InstanceModel.ID);
                // updateAll ON PURPOSE: it is hook-free, so a heal running in a JVM whose
                // capacity hook is already installed cannot double-book.
                instances.find().where(InstanceModel.ID.eq(id))
                    .assign(InstanceModel.CAPACITY_MB, booked)
                    .updateAll();
                Integer serverId = instance.get(InstanceModel.SERVER_ID);
                if (serverId != null && booked > 0) {
                    perHost.merge(serverId, (long) booked, Long::sum);
                }
            }
            for (Map.Entry<Integer, Long> entry : perHost.entrySet()) {
                Quotas.reserve(InstanceCapacity.bucketOf(entry.getKey()),
                    entry.getValue(), Long.MAX_VALUE);
            }
        });
    }
}
