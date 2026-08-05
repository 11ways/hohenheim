package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Persisted Docker-reconciler findings, replaced per server on every sweep so the
 * dashboard reads stored attribution instead of probing daemons per render.
 */
public class M050_CreateReconcileFindings extends HohenheimMigration {

    public M050_CreateReconcileFindings() {
        super("2026_08_03_000050", "Create reconcile findings");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("reconcile_findings", table -> {
            table.id();
            table.string("server_name");
            table.string("kind");
            table.string("resource_name");
            table.string("bucket");
            table.string("evidence");
            table.addColumn("owner_model", ColumnType.STRING, column -> column.nullable());
            table.addColumn("owner_id", ColumnType.STRING, column -> column.nullable());
            table.addColumn("detail", ColumnType.STRING, column -> column.nullable());
            table.timestamps();
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("reconcile_findings");
    }
}
