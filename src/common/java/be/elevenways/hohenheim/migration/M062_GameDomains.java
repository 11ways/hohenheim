package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

import java.util.List;

/**
 * Game-domains mappings plus attribution columns on instance files, so a generated
 * Velocity forced-hosts row carries owner + source metadata like a generated DNS row.
 *
 * The (site_domain_id, proxy_instance_id) unique index is safe on a NEW table (no live
 * data to heal); a domain may still map through several proxies.
 */
public class M062_GameDomains extends Migration {

    public M062_GameDomains() {
        super("2026_08_04_120000", "Game domain mappings");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("game_domains", table -> {
            table.id();
            table.integer("site_domain_id");
            table.integer("backend_instance_id");
            table.integer("proxy_instance_id");
            table.integer("backend_port");
            table.bool("enabled");
            table.timestamps();
            table.unique("game_domains_domain_proxy",
                List.of("site_domain_id", "proxy_instance_id"));
            table.addIndex("backend_instance_id");
        });
        schema.alterTable("instance_files", table -> {
            table.addColumn("generated_by", ColumnType.STRING,
                column -> column.maxLength(40).nullable().ifNotExists());
            table.addColumn("generated_for_model", ColumnType.STRING,
                column -> column.maxLength(120).nullable().ifNotExists());
            table.addColumn("generated_for_id", ColumnType.INTEGER,
                column -> column.nullable().ifNotExists());
            table.addColumn("generated_at", ColumnType.DATETIME,
                column -> column.nullable().ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("game_domains");
        schema.alterTable("instance_files", table -> {
            table.dropColumn("generated_at");
            table.dropColumn("generated_for_id");
            table.dropColumn("generated_for_model");
            table.dropColumn("generated_by");
        });
    }
}
