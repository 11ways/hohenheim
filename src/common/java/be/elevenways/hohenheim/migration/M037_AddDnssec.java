package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Per-zone DNSSEC signing key material and toggle (online-signing CSK).
 */
public class M037_AddDnssec extends HohenheimMigration {

    public M037_AddDnssec() {
        super("2026_07_17_000037", "Add per-zone DNSSEC key material");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("dns_zones", table -> {
            table.addColumn("dnssec_enabled", ColumnType.BOOLEAN, col -> col.defaultValue(false).ifNotExists());
            table.addColumn("dnssec_algorithm", ColumnType.INTEGER, col -> col.defaultValue(13).ifNotExists());
            table.addColumn("dnssec_private_key", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("dnssec_public_key", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("dnssec_key_tag", ColumnType.INTEGER, col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("dns_zones", table -> {
            table.dropColumn("dnssec_enabled");
            table.dropColumn("dnssec_algorithm");
            table.dropColumn("dnssec_private_key");
            table.dropColumn("dnssec_public_key");
            table.dropColumn("dnssec_key_tag");
        });
    }
}
