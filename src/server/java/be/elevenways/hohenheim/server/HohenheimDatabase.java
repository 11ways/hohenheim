package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;

/**
 * Database initialization and datasource management.
 */
public class HohenheimDatabase {

    private static SqliteDatasource datasource;

    public static void init() {
        String dbPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH);
        datasource = new SqliteDatasource("jdbc:sqlite:" + dbPath);

        // Make this the framework's default datasource so model singletons resolve it. Done here
        // (not just at boot) so test classes that re-init with a fresh DB stay in sync.
        Datasources.register("default", datasource);

        // Migrations are auto-discovered from the classpath: every public Migration subclass with a
        // public no-arg ctor whose datasource identifier matches, ordered by declared dependencies.
        // This covers Hohenheim's M0xx plus zenit-auth (auth_*) and zenit (system_task*) migrations.
        new MigrationRunner((MigrationCapableDatasource) datasource).migrate().requireSuccess();
    }

    public static SqliteDatasource datasource() {
        return datasource;
    }
}
