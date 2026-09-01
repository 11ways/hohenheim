package be.elevenways.hohenheim.migration;

import be.elevenways.hohenheim.security.BanScope;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Adds {@code bans.scope}: which traffic a ban refuses, so an SSH brute-forcer lands in
 * the port-22 nftables set instead of the 80/443 one.
 *
 * AIDEV-NOTE: nullable WITH the web default, so every row an installed controller already
 * stored keeps meaning exactly what it meant -- a web ban. InitialMigration is never
 * edited (see M002): starfleet stores its structural checksum.
 */
public class M008_BanScope extends HohenheimMigration {

    public M008_BanScope() {
        super("008", "Ban enforcement scope");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("bans", table ->
            table.addColumn("scope", ColumnType.STRING, column -> column
                .nullable(true)
                .maxLength(16)
                .defaultValue(BanScope.WEB.token())));
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("bans", table -> table.dropColumn("scope"));
    }
}
