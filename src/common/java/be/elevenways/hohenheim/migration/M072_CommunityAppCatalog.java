package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Per-template in-place app update script (the community-scripts update_script()
 * capability, adopted as a template-declared update action).
 */
public class M072_CommunityAppCatalog extends HohenheimMigration {

    public M072_CommunityAppCatalog() {
        super("2026_08_12_100000", "Community app catalog");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("instance_templates", table ->
            table.addColumn("update_script", ColumnType.TEXT,
                column -> column.nullable().ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instance_templates", table -> table.dropColumn("update_script"));
    }
}
