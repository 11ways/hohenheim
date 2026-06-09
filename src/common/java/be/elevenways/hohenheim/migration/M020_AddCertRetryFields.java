package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M020_AddCertRetryFields extends Migration {

    public M020_AddCertRetryFields() {
        super("2026_06_10_000020", "Add retry backoff fields to certificates");
    }

    @Override
    public void up(MigrationBuilder schema) {
        // Escalating-backoff state for failed ACME orders: how often issuance/renewal has
        // failed in a row, and when the renewal sweep may retry this certificate.
        schema.alterTable("certificates", table -> {
            table.addColumn("error_count", ColumnType.INTEGER, col -> col.defaultValue(0));
            table.addColumn("next_attempt_at", ColumnType.DATETIME, col -> col.nullable(true));
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("certificates", table -> {
            table.dropColumn("error_count");
            table.dropColumn("next_attempt_at");
        });
    }
}
