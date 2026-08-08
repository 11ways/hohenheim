package be.elevenways.hohenheim.server.migration;

import be.elevenways.hohenheim.migration.HohenheimMigration;
import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * The instance-database attachment table: what makes a managed database allocatable TO a
 * workload rather than only to a tenant.
 *
 * AIDEV-NOTE: no unique index on (instance_id, database_id). The duplicate refusal is a
 * NAMED violation in the resource, and the prefix collision it also has to refuse could
 * not be expressed as an index anyway (prefixes compare case-insensitively after
 * normalization); one gate that answers for both beats an index that answers for half and
 * turns the other half into an anonymous constraint error.
 *
 * @author Jelle De Loecker
 */
public class M087_CreateInstanceDatabases extends HohenheimMigration {

    public M087_CreateInstanceDatabases() {
        super("2026_09_03_100000", "Create the instance-database attachment table");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("instance_databases", table -> {
            table.id();
            table.integer("instance_id");
            table.integer("database_id");
            table.addColumn("env_prefix", ColumnType.STRING, col -> col.maxLength(40));
            table.timestamps();
            table.addIndex("instance_id");
            table.addIndex("database_id");
        });
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.dropTable("instance_databases");
    }
}
