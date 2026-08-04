package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.datasource.ColumnType;
import be.elevenways.zenit.common.orm.migration.Migration;
import be.elevenways.zenit.common.orm.migration.MigrationBuilder;

/**
 * Projects and environments (open decision 7, decided): the SCHEMA half only.
 * A project's ownership is grant-derived (its auth permission group holds the
 * manage grants), so there is no owner column here and no project_id on owned
 * records -- deliberately. The DATA half (adopting existing per-user-granted
 * records into projects) is the ledgered ProjectAdoptionSeeder, because it
 * writes zenit-auth tables that a hohenheim migration must not reach into.
 */
public class M067_ProjectsAndEnvironments extends Migration {

    public M067_ProjectsAndEnvironments() {
        super("2026_08_06_100000", "Projects and environments");
    }

    @Override
    public void up(MigrationBuilder schema) {
        schema.createTable("projects", table -> {
            table.id();
            table.string("name", 191);
            table.addColumn("description", ColumnType.TEXT, column -> column.nullable(true));
            // The zenit-auth permission group that IS this project's owner subject.
            // A plain integer, not a cross-module FK: auth tables may live on another
            // datasource, and the shipped SQLite URL does not enforce FKs anyway.
            table.addColumn("group_id", ColumnType.INTEGER, column -> column.nullable(true));
            table.timestamps();
        });

        schema.createTable("environments", table -> {
            table.id();
            table.foreignId("project_id", "projects");
            table.string("name", 191);
            table.addColumn("description", ColumnType.TEXT, column -> column.nullable(true));
            table.timestamps();
            table.addIndex("project_id");
        });

        schema.alterTable("instances", table -> table.addColumn("environment_id",
            ColumnType.INTEGER, column -> column.nullable(true).ifNotExists()));
        schema.alterTable("instance_variables", table -> table.addColumn("environment_id",
            ColumnType.INTEGER, column -> column.nullable(true).ifNotExists()));
    }

    @Override
    public void down(MigrationBuilder schema) {
        schema.alterTable("instance_variables", table -> table.dropColumn("environment_id"));
        schema.alterTable("instances", table -> table.dropColumn("environment_id"));
        schema.dropTable("environments");
        schema.dropTable("projects");
    }
}
