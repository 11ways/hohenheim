package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M024_AddProclogSavedAt extends Migration {

    public M024_AddProclogSavedAt() {
        super("2026_06_10_000024", "Add periodic-flush timestamp and listing index to proclogs");
    }

    @Override
    public void up(MigrationBuilder schema) {
        // When the rolling log was last flushed to this row (periodic UPSERT while the
        // process is alive, so a hard crash loses at most one flush interval).
        schema.alterTable("proclogs", table -> {
            table.addColumn("saved_at", ColumnType.DATETIME, col -> col.nullable(true));
            table.addIndex("site_id", "created_at");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("proclogs", table -> {
            table.dropIndex("proclogs_site_id_created_at_index");
            table.dropColumn("saved_at");
        });
    }
}
