package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M008_AddCertificateLifecycleFields extends Migration {

    public M008_AddCertificateLifecycleFields() {
        super("2026_04_02_000008", "Add lifecycle fields to certificates");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("certificates", table -> {
            table.addColumn("status", ColumnType.STRING,
                col -> col.maxLength(20).defaultValue("active").ifNotExists());
            table.addColumn("issued_on", ColumnType.DATETIME, col -> col.nullable(true).ifNotExists());
            table.addColumn("renewal_error", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
            table.addColumn("domain_names_text", ColumnType.TEXT, col -> col.nullable(true).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("certificates", table -> {
            table.dropColumn("status");
            table.dropColumn("issued_on");
            table.dropColumn("renewal_error");
            table.dropColumn("domain_names_text");
        });
    }
}
