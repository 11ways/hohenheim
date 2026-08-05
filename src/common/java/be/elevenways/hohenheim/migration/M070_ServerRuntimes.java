package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The host record's DECLARED runtime ({@code docker} or {@code incus}) plus the Incus
 * connection URL, so which daemon a host runs is data the dispatch reads, never an
 * assumption baked into a client constructor.
 */
public class M070_ServerRuntimes extends HohenheimMigration {

    public M070_ServerRuntimes() {
        super("2026_08_09_100000", "Server runtimes");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.alterTable("servers", table -> {
            table.addColumn("runtime", ColumnType.STRING,
                column -> column.maxLength(20).nullable(false).defaultValue("docker")
                    .ifNotExists());
            table.addColumn("incus_url", ColumnType.STRING,
                column -> column.maxLength(255).nullable().ifNotExists());
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("servers", table -> {
            table.dropColumn("incus_url");
            table.dropColumn("runtime");
        });
    }
}
