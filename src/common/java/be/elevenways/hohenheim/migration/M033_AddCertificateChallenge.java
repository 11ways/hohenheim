package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/** Stores how an ACME certificate can be renewed. */
public class M033_AddCertificateChallenge extends Migration {

    public M033_AddCertificateChallenge() {
        super("2026_07_16_000033", "Add ACME challenge and DNS publisher to certificates");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("certificates", table -> {
            table.addColumn("challenge_type", ColumnType.STRING,
                column -> column.maxLength(20).ifNotExists());
            table.addColumn("dns_publisher", ColumnType.STRING,
                column -> column.maxLength(80).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("certificates", table -> {
            table.dropColumn("dns_publisher");
            table.dropColumn("challenge_type");
        });
    }
}
