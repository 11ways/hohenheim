package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A managed database's pending password: the one a password change handed the engine before the record stored it,
 * kept (encrypted, like {@code db_password}) until the record and the engine agree.
 *
 * AIDEV-NOTE: nullable, and every row a live install holds reads "no change in flight"; no data step. A record that
 * drifted under a controller from before this migration has no pending value to recover with: its retry still fails
 * as it did. down() drops exactly what up() added.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M004_PendingPasswords extends HohenheimMigration {

    public M004_PendingPasswords() {
        super("2026_10_24_000002", "pending_passwords");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.alterTable("managed_databases", table ->
            table.addColumn("pending_password", ColumnType.STRING, column -> column.nullable(true).maxLength(256)));
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.alterTable("managed_databases", table -> table.dropColumn("pending_password"));
    }
}
