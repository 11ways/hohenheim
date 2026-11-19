package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Every schema change since the last published version: who holds a certificate's key, and the signing request a
 * requester-held certificate renews from.
 *
 * AIDEV-NOTE: UNPUBLISHED: every further schema change edits this migration until a live deploy publishes it. The
 * holder column carries the default every stored certificate already means (Hohenheim made its key), so existing rows
 * read as served exactly as before; the signing request is nullable because only a requester-held certificate keeps
 * one. down() undoes exactly what up() did.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M003_CertificateSigningRequests extends HohenheimMigration {

    public M003_CertificateSigningRequests() {
        super("2026_11_19_000001", "certificate_signing_requests");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.alterTable("certificates", table -> {
            table.addColumn("key_holder", ColumnType.STRING,
                column -> column.nullable(true).maxLength(32).defaultValue("hohenheim"));
            table.addColumn("signing_request_pem", ColumnType.TEXT, column -> column.nullable(true));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.alterTable("certificates", table -> {
            table.dropColumn("signing_request_pem");
            table.dropColumn("key_holder");
        });
    }
}
