package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The canonical runtime-resource lowering: instances grow the GeneratedRows attribution
 * columns so a product tier (Docker sites first) can OWN an instance as its running
 * release. No data heals: nothing could have written these columns before they existed,
 * and the pre-lowering site containers were owned by naming convention plus site labels,
 * which SiteInstances retires at first deploy.
 */
public class M064_SiteRuntimeInstances extends Migration {

    public M064_SiteRuntimeInstances() {
        super("2026_08_04_140000", "Site-owned runtime instances");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instances", table -> {
            table.addColumn("generated_by", ColumnType.STRING,
                column -> column.maxLength(100).nullable().ifNotExists());
            table.addColumn("generated_for_model", ColumnType.STRING,
                column -> column.maxLength(100).nullable().ifNotExists());
            table.addColumn("generated_for_id", ColumnType.INTEGER,
                column -> column.nullable().ifNotExists());
            table.addColumn("generated_at", ColumnType.DATETIME,
                column -> column.nullable().ifNotExists());
            table.addIndexIfNotExists("idx_instances_generated_for_id",
                java.util.List.of("generated_for_id"));
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instances", table -> {
            table.dropColumn("generated_at");
            table.dropColumn("generated_for_id");
            table.dropColumn("generated_for_model");
            table.dropColumn("generated_by");
        });
    }
}
