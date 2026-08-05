package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The health-gated release wave: a durable release/rollback operation record, plus the
 * {@code runtime_role} discriminator on instances (serving/candidate/retired) that lets
 * a site own TWO releases at once during a zero-downtime swap. No data heals: existing
 * instances are each their owner's only release, which the column default states.
 */
public class M066_ReleaseOperations extends HohenheimMigration {

    public M066_ReleaseOperations() {
        super("2026_08_05_110000", "Release operations and instance runtime roles");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("release_operations", table -> {
            table.id();
            table.addColumn("kind", ColumnType.STRING,
                column -> column.maxLength(50).defaultValue("release"));
            table.addColumn("for_model", ColumnType.STRING, column -> column.maxLength(100));
            table.addColumn("for_id", ColumnType.INTEGER, column -> column.nullable(true));
            table.addColumn("status", ColumnType.STRING,
                column -> column.maxLength(50).defaultValue("pending"));
            table.addColumn("image_id", ColumnType.STRING, column -> column.nullable(true));
            table.addColumn("candidate_instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("retired_instance_id", ColumnType.INTEGER,
                column -> column.nullable(true));
            table.addColumn("site_fingerprint", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("spec_fingerprint", ColumnType.STRING,
                column -> column.nullable(true));
            table.addColumn("failure_reason", ColumnType.STRING, column -> column.nullable(true));
            table.text("step_log");
            table.addColumn("started_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("finished_at", ColumnType.DATETIME, column -> column.nullable(true));
            table.addColumn("duration_ms", ColumnType.INTEGER, column -> column.nullable(true));
            table.timestamps();
            table.addIndex("for_id");
        });
        schema.alterTable("instances", table -> table.addColumn("runtime_role",
            ColumnType.STRING,
            column -> column.maxLength(20).defaultValue("serving").ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("release_operations");
        schema.alterTable("instances", table -> table.dropColumn("runtime_role"));
    }
}
