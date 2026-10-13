package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Adds {@code instances.game_address}, the name players type to reach a game server (its A/AAAA and SRV records are
 * generated into the zone that covers it).
 *
 * AIDEV-NOTE: a column written only through GameAddresses, never a settings key: a tenant with config on the instance
 * edits its settings, and a name in a managed zone is an operator's to give.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public class M013_InstanceGameAddress extends HohenheimMigration {

    public M013_InstanceGameAddress() {
        super("013", "Instance game address");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("instances", table ->
            table.addColumn("game_address", ColumnType.STRING, column -> column.nullable(true).maxLength(253)));
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("instances", table -> table.dropColumn("game_address"));
    }
}
