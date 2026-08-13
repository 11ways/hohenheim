package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.DatabaseEngine;
import be.elevenways.zenit.server.orm.DatasourceFactory;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;


/**
 * Database initialization and datasource management. SQLite only: the whole control
 * plane is written against one embedded engine (single-writer serialization is what
 * makes the route-claim registry correct), so {@code init()} refuses to construct
 * anything but a SQLite datasource.
 */
public class HohenheimDatabase {

    private static SqlDatasource datasource;

    /**
     * Opens the datasource and migrates it; migrations are auto-discovered from the classpath
     * (hohenheim's single InitialMigration plus zenit-auth auth_* and zenit system_task*),
     * never hand-listed.
     */
    public static void init() {
        openDatasource();
        new MigrationRunner(datasource).migrate().requireSuccess();
        // Mint/read the namespace token every daemon resource name carries, so it is in
        // the boot log before anything can be deployed under it.
        ControllerIdentity.resolve();
    }

    /**
     * Builds the SQLite datasource and registers it as the framework default, without
     * migrating: the migration-CLI path supplies it to ServerZenitRuntime, which owns
     * the run and closes it afterwards.
     *
     * @return the registered default datasource
     */
    public static SqlDatasource openDatasource() {
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
        return datasource;
    }

    public static SqlDatasource datasource() {
        return datasource;
    }

    /**
     * Closes the opened datasource; idempotent, so an offline command may close it EARLY
     * and the dispatcher's own finally block can still close unconditionally.
     *
     * AIDEV-NOTE: the reference is deliberately kept rather than nulled. Every offline
     * command reaches the database through {@link #datasource()} (ControlPlaneBackups does),
     * and the pre-existing shape after a CLI invocation was exactly "closed, still
     * referenced, process about to exit". Nulling it would turn that into an NPE instead
     * of the driver's own "closed" refusal, which says less.
     */
    public static void closeDatasource() {
        SqlDatasource open = datasource;
        if (open != null) {
            open.close();
        }
    }

    /**
     * Refuses any non-SQLite engine before a datasource exists.
     *
     * @throws IllegalStateException when the resolved engine is not SQLite
     */
    // AIDEV-NOTE: This guard is deliberately ABSOLUTE (no settings override). It used to be
    // justified by SQLite-only raw SQL inside the migration chain; that chain is gone (one
    // consolidated migration, portable operations only) but the guard is NOT, because the
    // reason that outlives it is stronger: RouteClaims' overlap refusal is guaranteed by
    // SQLite's single-writer transaction serialization, not by any index -- overlapping
    // (not equal) route claims spell DIFFERENT keys, so only a serialized scan can refuse
    // them. On a concurrent-writer engine two operators could both be handed the same
    // hostname. Every control-plane boot path (ServerMain, tests) funnels through init(),
    // so refusing here cannot be bypassed. Lifting this needs a real cross-writer claim
    // arbiter first, never a config flag.
    private static void requireSqlite(DatabaseEngine engine, String url) {
        if (engine == DatabaseEngine.SQLITE) {
            return;
        }
        throw new IllegalStateException(
            "Hohenheim's own database must be SQLite, but database.url/database.engine resolved to "
            + engine + " (" + url + "). The control plane depends on SQLite's single-writer "
            + "transaction serialization: RouteClaims refuses OVERLAPPING route claims inside one "
            + "serialized write transaction, and no unique index can express that overlap, so a "
            + "concurrent-writer engine would silently hand two sites the same hostname. "
            + "Leave database.url blank and set database.path to a SQLite file instead. "
            + "There is deliberately no override.");
    }
}
