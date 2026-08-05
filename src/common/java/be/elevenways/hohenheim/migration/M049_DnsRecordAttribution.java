package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Attribution for DNS rows a system authored: source, declaring record and timestamp.
 *
 * Deliberately not unique and not indexed: two concurrent ACME orders for one name write
 * two rows with identical attribution, and nothing queries by it yet.
 *
 * @author Jelle De Loecker
 */
public class M049_DnsRecordAttribution extends HohenheimMigration {

    public M049_DnsRecordAttribution() {
        super("2026_08_03_000049", "Attribute system-generated DNS records");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("dns_records", table -> {
            table.addColumn("generated_by", ColumnType.STRING,
                column -> column.maxLength(40).nullable().ifNotExists());
            table.addColumn("generated_for_model", ColumnType.STRING,
                column -> column.maxLength(120).nullable().ifNotExists());
            table.addColumn("generated_for_id", ColumnType.INTEGER,
                column -> column.nullable().ifNotExists());
            table.addColumn("generated_at", ColumnType.DATETIME,
                column -> column.nullable().ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("dns_records", table -> {
            table.dropColumn("generated_at");
            table.dropColumn("generated_for_id");
            table.dropColumn("generated_for_model");
            table.dropColumn("generated_by");
        });
    }
}
