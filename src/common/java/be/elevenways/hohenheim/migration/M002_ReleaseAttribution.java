package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * What started each release operation and what it deployed: the trigger, the accountable principal, the commit, the
 * build and the redacted spec snapshot.
 *
 * AIDEV-NOTE: every column is nullable, so the rows a live install already holds stay valid and read as "not
 * recorded"; no data step, since nothing recorded these facts before. down() drops exactly what up() added.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class M002_ReleaseAttribution extends HohenheimMigration {

    public M002_ReleaseAttribution() {
        super("2026_10_23_000001", "release_attribution");
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
    }

    @Override
    public void down(@NonNull MigrationBuilder builder) {
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
