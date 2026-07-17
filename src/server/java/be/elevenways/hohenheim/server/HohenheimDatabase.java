package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.DatabaseEngine;
import be.elevenways.zenit.server.orm.DatasourceFactory;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;

/**
 * Database initialization and datasource management. SQLite is the zero-config
 * default; any of the framework's relational engines can be used instead by
 * setting {@code database.url} (and, for CockroachDB, {@code database.engine}).
 */
public class HohenheimDatabase {

    private static SqlDatasource datasource;

    public static void init() {
        String url = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.URL);
        if (url == null || url.isBlank()) {
            String dbPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PATH);
            url = "jdbc:sqlite:" + dbPath;
        }
        String engineName = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.ENGINE);
        String username = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.USERNAME);
        String password = HohenheimSettings.VALUES.getValue(HohenheimSettings.Database.PASSWORD);

        DatabaseEngine engine = DatabaseEngine.resolve(engineName, url);
        datasource = DatasourceFactory.create(engine, url, username, password);

        // Make this the framework's default datasource so model singletons resolve it. Done here
        // (not just at boot) so test classes that re-init with a fresh DB stay in sync.
        Datasources.register("default", datasource);

        // Migrations are auto-discovered from the classpath: every public Migration subclass with a
        // public no-arg ctor whose datasource identifier matches, ordered by declared dependencies.
        // This covers Hohenheim's M0xx plus zenit-auth (auth_*) and zenit (system_task*) migrations.
        new MigrationRunner(datasource).migrate().requireSuccess();
    }

    public static SqlDatasource datasource() {
        return datasource;
    }
}
