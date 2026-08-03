package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Phase 6's console-matcher wiring: the per-instance crash policy the console watcher
 * enforces on an unexpected exit (the readiness/stop matcher data itself already lives
 * on instance_templates since M059).
 */
public class M060_InstanceCrashPolicy extends Migration {

    public M060_InstanceCrashPolicy() {
        super("2026_08_03_190000", "Instance crash policy");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instances", table -> {
            table.addColumn("crash_policy", ColumnType.STRING,
                col -> col.maxLength(50).defaultValue("none").ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> {
            table.dropColumn("crash_policy");
        });
    }
}
