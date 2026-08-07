package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;

/**
 * Durable console history for the instance tier: one upserted row per workload episode,
 * so the observability surface survives a controller restart and has a retention policy
 * instead of an unbounded in-memory ring.
 *
 * @author Jelle De Loecker
 */
public class M085_InstanceLogs extends HohenheimMigration {

    public M085_InstanceLogs() {
        super("2026_09_01_100000", "Instance console logs");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("instance_logs", table -> {
            table.id();
            table.addColumn("instance_id", ColumnType.INTEGER, col -> col.nullable(false));
            table.addColumn("handle", ColumnType.STRING,
                col -> col.maxLength(191).nullable(true));
            table.addColumn("log_text", ColumnType.TEXT, col -> col.nullable(true));
            table.addColumn("line_count", ColumnType.INTEGER, col -> col.nullable(true));
            table.addColumn("saved_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.timestamps();
            // Retention sweeps by created_at and the console tab reads by instance.
            table.addIndex("instance_logs_instance_created",
                List.of("instance_id", "created_at"));
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("instance_logs");
    }
}
