package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The schema changes after M003: the table in which the outage watch of an app's site remembers it between its sweeps,
 * and the app a Docker container takes its image from.
 *
 * AIDEV-NOTE: PUBLISHED: kuifje and robbedoes applied it on 2026-11-23, so it is frozen (pinned in
 * migration-pins.txt). A schema change from now on is a new M005; never edit this one again. The table starts empty:
 * an existing site reads as never seen answering, so the first sweep after the upgrade alerts nothing.
 * instances.image_app_id starts null on every row, so every existing container keeps its own image. down() undoes
 * exactly what up() did, the index before its column.
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
        builder.alterTable("instances", table -> {
            table.addColumn("image_app_id", ColumnType.INTEGER, column -> column.nullable(true));
            table.addIndex("instances_image_app_id_index", List.of("image_app_id"));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.alterTable("instances", table -> {
            table.dropIndex("instances_image_app_id_index");
            table.dropColumn("image_app_id");
        });
        builder.dropTable("site_outages");
    }
}
