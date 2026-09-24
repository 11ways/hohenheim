package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.ForeignKeyAction;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Adds {@code install_media_fetches}: the stored status of an install-media fetch that now runs off the
 * request thread. A new table only; no existing row is read or rewritten, so the old jar keeps working.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public class M015_InstallMediaFetches extends HohenheimMigration {

    public M015_InstallMediaFetches() {
        super("015", "Install media fetches");
        // The stream chain (see HohenheimMigration): 012 waits for zenit-auth, so without
        // this edge 015 would run BEFORE it and an install at 012 would refuse to boot.
        dependsOn(M012_SiteTrustedUpstream.class);
    }

    @Override
    public void up(@NonNull MigrationBuilder schema) {
        schema.createTable("install_media_fetches", table -> {
            table.id();
            table.addColumn("server_id", ColumnType.INTEGER, column -> column.nullable(false)
                .references("servers", "id").onDelete(ForeignKeyAction.CASCADE));
            table.addColumn("name", ColumnType.STRING, column -> column.nullable(false).maxLength(64));
            table.addColumn("state", ColumnType.STRING, column -> column.nullable(false).maxLength(16));
            table.addColumn("progress", ColumnType.DOUBLE, column -> column.nullable(true));
            table.addColumn("error", ColumnType.TEXT, column -> column.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.timestamps();
            table.addIndex("install_media_fetches_server", List.of("server_id", "created_at"));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder schema) {
        schema.dropTable("install_media_fetches");
    }
}
