package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * Every schema change since the published baseline: release attribution, credential generations, pending passwords
 * and build hooks.
 *
 * AIDEV-NOTE: THE one migration after the last published version (M001, recorded on kuifje and robbedoes on
 * 2026-10-21). Until the next live deploy publishes it, every schema change edits THIS migration; never add an M003.
 * Every column is nullable, so the rows a live install holds stay valid and read as "not recorded": generations read
 * as 0 ("never rotated, deployed with it"), a pending password as "no change in flight"; no data step. down() drops
 * exactly what up() added.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class M002_AfterBaseline extends HohenheimMigration {

    public M002_AfterBaseline() {
        super("2026_10_23_000001", "after_baseline");
    }

    @Override
    public void up(@NonNull MigrationBuilder builder) {
        builder.alterTable("release_operations", table -> {
            table.addColumn("deploy_trigger", ColumnType.STRING, column -> column.nullable(true).maxLength(32));
            table.addColumn("triggered_by", ColumnType.UUID, column -> column.nullable(true));
            // Spelled out as PrincipalColumns.addKindColumn declares it, so a helper change never moves this digest.
            table.addColumn("triggered_by_kind", ColumnType.STRING, column -> column.nullable(true).maxLength(100));
            table.addColumn("commit_sha", ColumnType.STRING, column -> column.nullable(true).maxLength(100));
            table.addColumn("build_operation_id", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("spec_snapshot", ColumnType.TEXT, column -> column.nullable(true));
        });
        builder.alterTable("managed_databases", table -> {
            table.addColumn("credential_generation", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("pending_password", ColumnType.STRING, column -> column.nullable(true).maxLength(256));
        });
        builder.alterTable("instance_databases", table ->
            table.addColumn("deployed_generation", ColumnType.INTEGER, column -> column.nullable(true)));
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
        builder.alterTable("instance_databases", table -> table.dropColumn("deployed_generation"));
        builder.alterTable("managed_databases", table -> {
            table.dropColumn("pending_password");
            table.dropColumn("credential_generation");
        });
        builder.alterTable("release_operations", table -> {
            table.dropColumn("spec_snapshot");
            table.dropColumn("build_operation_id");
            table.dropColumn("commit_sha");
            table.dropColumn("triggered_by_kind");
            table.dropColumn("triggered_by");
            table.dropColumn("deploy_trigger");
        });
    }
}
