package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The schema changes after M003: the table in which the outage watch of an app's site remembers it between its sweeps.
 *
 * AIDEV-NOTE: OPEN: no live host applied it yet, so it is the one migration after PUBLISHED and every schema change
 * until the next live deploy edits it. The table starts empty: an existing site reads as never seen answering, so the
 * first sweep after the upgrade alerts nothing. down() undoes exactly what up() did.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M004_SiteOutages extends HohenheimMigration {

    public M004_SiteOutages() {
        super("2026_11_23_000001", "site_outages");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.createTable("site_outages", table -> {
            table.id();
            table.addColumn("site_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("sites", "id"));
            table.addColumn("up_seen_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("down_since", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("down_alerted_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.timestamps();
            table.unique("site_outages_site_id_unique", List.of("site_id"));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.dropTable("site_outages");
    }
}
