package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M031_AddCertExpiryStamp extends Migration {

    public M031_AddCertExpiryStamp() {
        super("2026_07_08_000031", "Add the expiring-soon alert dedup stamp to certificates");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("certificates", table ->
            table.addColumn("expiry_notified_at", ColumnType.DATETIME, col -> col.nullable(true)));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("certificates", table -> table.dropColumn("expiry_notified_at"));
    }
}
