package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Drops columns that were declared but never read by any code path: per-domain
 * port/http2/certificate_type (routing never consulted them) and the certificate
 * DNS-01/custom-ACME/meta placeholders (features never built).
 */
public class M027_DropDeadColumns extends HohenheimMigration {

    public M027_DropDeadColumns() {
        super("2026_07_07_000027", "Drop dead site_domain and certificate columns");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("site_domains", table -> {
            table.dropColumn("port");
            table.dropColumn("http2_support");
            table.dropColumn("certificate_type");
        });
        schema.alterTable("certificates", table -> {
            table.dropColumn("dns_provider");
            table.dropColumn("dns_credentials");
            table.dropColumn("acme_server");
            table.dropColumn("meta");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("site_domains", table -> {
            table.addColumn("port", ColumnType.INTEGER, col -> col.nullable(true).ifNotExists());
            table.addColumn("http2_support", ColumnType.BOOLEAN, col -> col.defaultValue(true).ifNotExists());
            table.addColumn("certificate_type", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
        });
        schema.alterTable("certificates", table -> {
            table.addColumn("dns_provider", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("dns_credentials", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("acme_server", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("meta", ColumnType.JSON, col -> col.nullable(true).ifNotExists());
        });
    }
}
