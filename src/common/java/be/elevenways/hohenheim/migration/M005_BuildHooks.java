package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * The build hooks: per application, named secret URLs whose POST deploys it, stored as a keyed digest of the token.
 *
 * AIDEV-NOTE: a new table, so no live row needs a default or a data step. The digest is unique (one hook per token)
 * and the instance index serves the Build hooks tab. down() drops exactly the table up() created.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M005_BuildHooks extends HohenheimMigration {

    public M005_BuildHooks() {
        super("2026_10_24_000003", "build_hooks");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.createTable("build_hooks", table -> {
            table.addColumn("id", ColumnType.UUID, column -> column.primaryKey());
            table.addColumn("instance_id", ColumnType.INTEGER,
                column -> column.nullable(false).references("instances", "id"));
            table.addColumn("name", ColumnType.STRING, column -> column.nullable(true).maxLength(100));
            table.addColumn("token_digest", ColumnType.STRING, column -> column.nullable(true).maxLength(128));
            table.addColumn("url_preview", ColumnType.STRING, column -> column.nullable(true).maxLength(128));
            table.addColumn("revoked_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("last_triggered_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("created_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("updated_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.unique("build_hooks_token_digest_unique", List.of("token_digest"));
            table.addIndex("build_hooks_instance_id_index", List.of("instance_id"));
        });
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
        builder.dropTable("build_hooks");
    }
}
