package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The buildpack lane's inspectable detection record: what the nixpacks detector saw and
 * what it emitted, on the build operation itself. No data heals -- every pre-existing
 * row is a dockerfile build, whose detection is legitimately null.
 */
public class M069_BuildDetection extends HohenheimMigration {

    public M069_BuildDetection() {
        super("2026_08_08_100000", "Build detection record");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("build_operations", table ->
            table.addColumn("detection", ColumnType.TEXT,
                column -> column.nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("build_operations", table -> table.dropColumn("detection"));
    }
}
