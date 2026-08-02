package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Records WHO a certificate was ordered for, so a renewal can re-decide their authority
 * instead of inheriting the fact that issuance once succeeded.
 *
 * @author Jelle De Loecker
 */
public class M048_CertificateRequester extends Migration {

    public M048_CertificateRequester() {
        super("2026_08_03_000048", "Record the subject a certificate was ordered for");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("certificates", table ->
            table.addColumn("requested_by_user_id", ColumnType.INTEGER,
                column -> column.nullable().ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("certificates", table -> table.dropColumn("requested_by_user_id"));
    }
}
