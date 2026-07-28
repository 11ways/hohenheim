package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

public class M010_AddDomainLeExclude extends Migration {

    public M010_AddDomainLeExclude() {
        super("2026_04_02_000010", "Add exclude_from_letsencrypt to site_domains");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("site_domains", table -> {
            table.addColumn("exclude_from_letsencrypt", ColumnType.BOOLEAN,
                col -> col.defaultValue(false).ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("site_domains", table -> table.dropColumn("exclude_from_letsencrypt"));
    }
}
