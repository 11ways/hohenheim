package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Adds {@code database_engines} (one host-shared engine process serving many logical
 * databases) and the placement of every managed database: {@code placement} plus the
 * {@code engine_id} a shared record is bound to.
 *
 * AIDEV-NOTE: both new columns are nullable and unset for every existing row, and a
 * null placement READS as dedicated -- which is what every pre-existing record is, so
 * nothing is rewritten and the old jar keeps reading the table. InitialMigration is
 * never edited (see M002).
 */
public class M009_SharedDatabaseEngines extends HohenheimMigration {

    public M009_SharedDatabaseEngines() {
        super("009", "Shared database engines");
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.createTable("database_engines", table -> {
            table.id();
            table.addColumn("name", ColumnType.STRING,
                column -> column.nullable(false).maxLength(64));
            table.addColumn("engine", ColumnType.STRING,
                column -> column.nullable(false).maxLength(32));
            table.addColumn("image", ColumnType.STRING,
                column -> column.nullable(true).maxLength(255));
            table.addColumn("server_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("servers", "id"));
            table.addColumn("root_user", ColumnType.STRING,
                column -> column.nullable(false).maxLength(64));
            table.addColumn("root_password", ColumnType.TEXT,
                column -> column.nullable(false));
            table.addColumn("memory_limit_mb", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("cpu_limit", ColumnType.DOUBLE,
                column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.nullable(false).maxLength(32));
            table.addColumn("failure_reason", ColumnType.TEXT,
                column -> column.nullable(true));
            table.timestamps();
            table.unique("name");
        });
        schema.alterTable("managed_databases", table -> {
            table.addColumn("placement", ColumnType.STRING, column -> column
                .nullable(true)
                .maxLength(16));
            table.addColumn("engine_id", ColumnType.INTEGER, column -> column
                .nullable(true)
                .references("database_engines", "id"));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.alterTable("managed_databases", table -> {
            table.dropColumn("engine_id");
            table.dropColumn("placement");
        });
        schema.dropTable("database_engines");
    }
}
