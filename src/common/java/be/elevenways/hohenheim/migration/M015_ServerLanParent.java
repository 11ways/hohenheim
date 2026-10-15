package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Adds {@code servers.lan_parent}, the host interface its virtual machines may join the local network through.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public class M015_ServerLanParent extends HohenheimMigration {

    public M015_ServerLanParent() {
        super("015", "Server LAN parent");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("servers", table ->
            table.addColumn("lan_parent", ColumnType.STRING, column -> column.nullable(true).maxLength(15)));
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("servers", table -> table.dropColumn("lan_parent"));
    }
}
