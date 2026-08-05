package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Phase 4's snapshot/backup half: backup targets (the target seam's data), snapshot
 * rows and backup rows as DISTINCT records (a snapshot dies with the host, a backup
 * leaves it), plus the per-instance backup wiring columns.
 */
public class M058_SnapshotsAndBackups extends HohenheimMigration {

    public M058_SnapshotsAndBackups() {
        super("2026_08_03_170000", "Instance snapshots, backups and backup targets");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("backup_targets", table -> {
            table.id();
            table.string("name", 255);
            table.string("kind", 100);
            table.addColumn("settings", ColumnType.JSON, col -> col.nullable(true));
            table.timestamps();
        });

        schema.createTable("instance_snapshots", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                col -> col.references("instances", "id"));
            table.addColumn("status", ColumnType.STRING,
                col -> col.maxLength(50).defaultValue("failed"));
            table.addColumn("note", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("directory", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("volumes", ColumnType.JSON, col -> col.nullable(true));
            table.addColumn("total_bytes", ColumnType.LONG, col -> col.nullable(true));
            table.text("error");
            table.timestamps();
            table.addIndex("instance_id");
        });

        schema.createTable("instance_backups", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER,
                col -> col.references("instances", "id"));
            table.addColumn("target_id", ColumnType.INTEGER,
                col -> col.nullable(true).references("backup_targets", "id"));
            table.addColumn("status", ColumnType.STRING,
                col -> col.maxLength(50).defaultValue("failed"));
            table.addColumn("remote_key", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("sha256", ColumnType.STRING, col -> col.nullable(true));
            table.addColumn("size_bytes", ColumnType.LONG, col -> col.nullable(true));
            table.addColumn("summary", ColumnType.JSON, col -> col.nullable(true));
            table.text("error");
            table.timestamps();
            table.addIndex("instance_id");
        });

        schema.alterTable("instances", table -> {
            table.addColumn("backup_enabled", ColumnType.BOOLEAN,
                col -> col.defaultValue(false).ifNotExists());
            table.addColumn("backup_target_id", ColumnType.INTEGER,
                col -> col.nullable(true).references("backup_targets", "id").ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> {
            table.dropColumn("backup_target_id");
            table.dropColumn("backup_enabled");
        });
        schema.dropTable("instance_backups");
        schema.dropTable("instance_snapshots");
        schema.dropTable("backup_targets");
    }
}
