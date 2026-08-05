package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M034_CreateDnsZones extends HohenheimMigration {

    public M034_CreateDnsZones() {
        super("2026_07_17_000034", "Create the authoritative DNS zone and record tables");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("dns_zones", table -> {
            table.id();
            table.string("origin", 255);
            table.unique("origin");
            table.string("soa_primary_ns", 255);
            table.string("soa_contact", 255);
            table.addColumn("serial", ColumnType.INTEGER, col -> col.defaultValue(1));
            table.addColumn("default_ttl", ColumnType.INTEGER, col -> col.defaultValue(3600));
            table.addColumn("negative_ttl", ColumnType.INTEGER, col -> col.defaultValue(300));
            table.addColumn("soa_refresh", ColumnType.INTEGER, col -> col.defaultValue(7200));
            table.addColumn("soa_retry", ColumnType.INTEGER, col -> col.defaultValue(3600));
            table.addColumn("soa_expire", ColumnType.INTEGER, col -> col.defaultValue(1209600));
            table.addColumn("enabled", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.timestamps();
        });

        schema.createTable("dns_records", table -> {
            table.id();
            table.foreignId("zone_id", "dns_zones");
            table.string("name", 255);
            table.addColumn("type", ColumnType.STRING, col -> col.maxLength(10));
            table.addColumn("ttl", ColumnType.INTEGER, col -> col.nullable(true));
            table.string("value", 4096);
            table.addColumn("priority", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("weight", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("port", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("enabled", ColumnType.BOOLEAN, col -> col.defaultValue(true));
            table.addColumn("managed_by", ColumnType.STRING, col -> col.maxLength(40).nullable(true));
            table.timestamps();
            table.addIndex("zone_id");
            table.addIndex("name");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("dns_records");
        schema.dropTable("dns_zones");
    }
}
