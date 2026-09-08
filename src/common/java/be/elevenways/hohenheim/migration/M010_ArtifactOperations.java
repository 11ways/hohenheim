package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;
import org.checkerframework.checker.nullness.qual.NonNull;
import java.util.List;

/** Upload receipts and successful immutable source history; no existing data rewritten. */
public class M010_ArtifactOperations extends HohenheimMigration {
    public M010_ArtifactOperations() { super("010", "Artifact operations"); }
    @Override public void up(@NonNull MigrationBuilder schema) {
        schema.createTable("artifact_operations", table -> {
            table.id();
            table.addColumn("application_id", ColumnType.INTEGER, c -> c.nullable(false).references("instances", "id"));
            table.addColumn("site_id", ColumnType.INTEGER, c -> c.nullable(false).references("sites", "id"));
            table.addColumn("status", ColumnType.STRING, c -> c.nullable(false).maxLength(32));
            table.addColumn("artifact_sha256", ColumnType.STRING, c -> c.nullable(false).maxLength(64));
            table.addColumn("instance_id", ColumnType.INTEGER, c -> c.nullable(true));
            table.addColumn("image_id", ColumnType.STRING, c -> c.nullable(true).maxLength(255));
            table.addColumn("error", ColumnType.STRING, c -> c.nullable(true).maxLength(255));
            table.addColumn("finished_at", ColumnType.DATETIME, c -> c.nullable(true));
            table.timestamps();
            table.addIndex("artifact_operations_source", List.of("application_id", "status", "finished_at"));
        });
        schema.createTable("artifact_sources", table -> {
            table.addColumn("application_id", ColumnType.INTEGER, c -> c.primaryKey().references("instances", "id"));
            table.addColumn("artifact_sha256", ColumnType.STRING, c -> c.nullable(false).maxLength(64));
            table.timestamps();
        });
    }
    @Override public void down(@NonNull MigrationBuilder schema) {
        schema.dropTable("artifact_sources");
        schema.dropTable("artifact_operations");
    }
}
