package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M021_AddCertLetsencryptEmail extends Migration {

    public M021_AddCertLetsencryptEmail() {
        super("2026_06_10_000021", "Add per-certificate Let's Encrypt account email");
    }

    @Override
    public void up(MigrationBuilder schema) {
        // Also set on provider='acme_account' rows: the ACME account key store is keyed
        // by this column (NULL = the global default account).
        schema.alterTable("certificates", table ->
            table.addColumn("letsencrypt_email", ColumnType.STRING,
                col -> col.maxLength(255).nullable(true)));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("certificates", table ->
            table.dropColumn("letsencrypt_email"));
    }
}
