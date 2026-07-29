package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.DatabaseEngine;
import be.elevenways.zenit.server.orm.DatasourceFactory;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;

import java.util.List;

/**
 * Database initialization and datasource management. SQLite only: several
 * migrations run raw SQLite SQL (M025 {@code json_type}, M043 {@code ||}
 * concatenation, which MySQL parses as logical OR and silently corrupts data),
 * so {@code init()} refuses to construct anything but a SQLite datasource.
 */
public class HohenheimDatabase {

    /**
     * Versions of migrations that were deleted from the source tree while their history rows
     * stayed behind; every runner must acknowledge them or report them as integrity findings.
     */
    // AIDEV-NOTE: M001_CreateUsers, M002_CreateOrganizations and M007_CreateSessions were deleted
    // in 3678bd9 (the zenit-auth cutover). Without this acknowledgement every boot logs an
    // integrity finding for each of them, and switching database.migration_integrity to "fail"
    // would refuse to start. Do NOT delete the rows: they are the audit trail of what actually ran
    // on that install. Anything retired in the future is appended here in the same commit.
    public static final List<String> RETIRED_MIGRATION_VERSIONS = List.of(
        "2026_03_31_000001",    // M001_CreateUsers
        "2026_03_31_000002",    // M002_CreateOrganizations
        "2026_04_02_000007"     // M007_CreateSessions
    );

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
        requireSqlite(engine, url);
        datasource = DatasourceFactory.create(engine, url, username, password);

        // Make this the framework's default datasource so model singletons resolve it. Done here
        // (not just at boot) so test classes that re-init with a fresh DB stay in sync.
        Datasources.register("default", datasource);

        // Migrations are auto-discovered from the classpath: every public Migration subclass with a
        // public no-arg ctor whose datasource identifier matches, ordered by declared dependencies.
        // This covers Hohenheim's M0xx plus zenit-auth (auth_*) and zenit (system_task*) migrations.
        new MigrationRunner(datasource)
            .acknowledgeMissingMigrationVersions(RETIRED_MIGRATION_VERSIONS.toArray(new String[0]))
            .migrate()
            .requireSuccess();
    }

    public static SqlDatasource datasource() {
        return datasource;
    }

    /**
     * Refuses any non-SQLite engine before a datasource exists.
     *
     * @throws IllegalStateException when the resolved engine is not SQLite
     */
    // AIDEV-NOTE: This guard is deliberately ABSOLUTE (no settings override). The migration
    // history contains SQLite-only raw SQL: M025 uses the JSON1 json_type() function and
    // M043 uses || string concatenation, which MySQL parses as logical OR and silently
    // turns a stack service name into 0/1 -- data corruption, not a syntax error. Every
    // control-plane boot path (ServerMain, tests) funnels through init(), so refusing here
    // cannot be bypassed. Lifting this requires porting the raw SQL in every applied
    // migration first, never a config flag.
    private static void requireSqlite(DatabaseEngine engine, String url) {
        if (engine == DatabaseEngine.SQLITE) {
            return;
        }
        throw new IllegalStateException(
            "Hohenheim's own database must be SQLite, but database.url/database.engine resolved to "
            + engine + " (" + url + "). The migration history contains SQLite-only raw SQL: "
            + "M025 uses the JSON1 json_type() function, and M043 uses || string concatenation, "
            + "which MySQL parses as logical OR and would silently corrupt stack service names. "
            + "Leave database.url blank and set database.path to a SQLite file instead. "
            + "There is deliberately no override: running these migrations on another engine "
            + "either fails or corrupts data.");
    }
}
