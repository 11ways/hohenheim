package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The sandboxed-builder wave's one table: a build operation shared by every builder
 * kind. No data heals -- the pre-sandbox build path kept no record at all (it called
 * the daemon's own /build inline from the site convergence), so there is nothing to
 * migrate forward.
 */
public class M065_BuildOperations extends HohenheimMigration {

    public M065_BuildOperations() {
        super("2026_08_05_100000", "Sandboxed build operations");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("build_operations", table -> {
            table.id();
            table.addColumn("builder_kind", ColumnType.STRING,
                column -> column.maxLength(50).defaultValue("dockerfile"));
            table.addColumn("for_model", ColumnType.STRING, column -> column.maxLength(100));
            table.addColumn("for_id", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.maxLength(50).defaultValue("running"));
            table.addColumn("source_ref", ColumnType.STRING, column -> column.nullable(true));
            table.addColumn("image_id", ColumnType.STRING, column -> column.nullable(true));
            table.addColumn("tag", ColumnType.STRING, column -> column.nullable(true));
            table.addColumn("exit_code", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("failure_reason", ColumnType.STRING, column -> column.nullable(true));
            table.text("log");
            table.addColumn("cpu_limit", ColumnType.DOUBLE, column -> column.nullable(true));
            table.addColumn("memory_limit_mb", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("disk_limit_mb", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("pids_limit", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("timeout_seconds", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("peak_disk_bytes", ColumnType.LONG, column -> column.nullable(true));
            table.addColumn("artifact_bytes", ColumnType.LONG, column -> column.nullable(true));
            table.addColumn("started_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("duration_ms", ColumnType.INTEGER, column -> column.nullable(true));
            table.timestamps();
            table.addIndex("for_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("build_operations");
    }
}
