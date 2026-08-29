package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Adds {@code managed_databases.failure_reason}: why a provision that ended in status
 * failed did, shown on the record and in the attention item.
 *
 * AIDEV-NOTE: the FIRST migration appended after the 2026-08-13 consolidation, and the
 * reason the fold's "edit InitialMigration in place" doctrine ended. Starfleet is a
 * deployed installation whose {@code zenit_migrations} row for version 001 stores that
 * migration's structural checksum, so an in-place edit makes the next boot refuse under
 * the shipped {@code database.migration_integrity=fail} -- which is precisely what
 * happened when this column first landed inside InitialMigration (b3e9e840, reverted).
 */
public class M002_ManagedDatabaseFailureReason extends HohenheimMigration {

    public M002_ManagedDatabaseFailureReason() {
        super("002", "Managed database failure reason");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.alterTable("managed_databases", table ->
            table.addColumn("failure_reason", ColumnType.TEXT,
                column -> column.nullable(true)));
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("managed_databases", table -> table.dropColumn("failure_reason"));
    }
}
