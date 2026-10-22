package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.test.live.LiveNamespaces;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.orm.SqliteTemplates;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.seed.Seeds;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.task.TaskRuntime;
import be.elevenways.zenit.test.support.TestSqliteFiles;

import java.io.File;
import java.nio.file.Path;

/**
 * Hands out freshly-migrated databases to tests without paying for the migrations every time.
 *
 * AIDEV-NOTE: {@link HohenheimDatabase#init()} always runs the full migration set, about four seconds on an empty
 * SQLite file. Every fresh file is first restored from zenit's machine-wide migrated template ({@link SqliteTemplates},
 * keyed by the migrations and the code that shapes them), so {@code migrate()} finds its history table populated and
 * no-ops; only the first fork on the machine after a migration change migrates. This replaced a per-invocation
 * template directory of its own, which made every gradle run and every forked child JVM migrate once more.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    /**
     * Point the runtime at a private, already-migrated database and re-initialize.
     *
     * @return the database file, which is deleted when the JVM exits
     */
    public static synchronized File freshDatabase() throws Exception {
        File db = File.createTempFile("hohenheim-test", ".db");
        db.delete();
        db.deleteOnExit();
        restoreTemplate(db.toPath());

        SqlDatasource outgoing = HohenheimDatabase.datasource();
        releaseHandBoundDefault();

        // The test database file, committing without a disk flush (TestSqliteFiles: production's journal and locking).
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.PATH, TestSqliteFiles.reference(db.toPath()));
        HohenheimDatabase.init();
        closeOutgoing(outgoing);
        remintControllerIdentity();
        rebindDatasourceBoundServices();

        // proxy.force_https defaults ON and now fails CLOSED (503) whenever no certificate
        // is loaded -- which is every cleartext test proxy. The test baseline turns it off;
        // the force-SSL availability tests re-enable it explicitly.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS, false);

        return db;
    }

    /** Fills the new database file from the machine's migrated template. */
    private static void restoreTemplate(Path db) {
        SqliteDatasource filling = new SqliteDatasource(TestSqliteFiles.url(db));
        try {
            SqliteTemplates.restoreIfEmpty(filling);
        } finally {
            filling.close();
        }
    }

    /**
     * Release a default datasource some class bound by hand and left bound, so the product's next open can bind its
     * own; the datasource {@code HohenheimDatabase} opened itself stays, its re-init supersedes it.
     *
     * AIDEV-NOTE: a class whose subject is migrating binds its own database as the default by hand and leaves it
     * bound; the default registry refuses a second binding (no silent last-wins). Every shared-JVM reset point, and
     * every class that opens the product's database itself (an upgrade fixture, an empty-database boot), calls this
     * first: skipping it fails that class only when a hand-binding class ran before it in the same fork.
     */
    public static synchronized void releaseHandBoundDefault() {
        Datasource bound = Datasources.getDefault();
        if (bound != null && bound != HohenheimDatabase.datasource()) {
            Datasources.unregister(Datasources.DEFAULT, bound);
        }
    }

    /**
     * The registered default datasource, freshly migrated and private to the caller.
     *
     * AIDEV-NOTE: this exists to kill a copy-pasted fixture. 82 classes hand-rolled
     * "temp file -> new SqliteDatasource -> new MigrationRunner(ds).migrate() ->
     * Datasources.replace(DEFAULT, ds)", which bypasses the template entirely (and made
     * ensureDatasource early-return, so nothing downstream could rescue it): 45 full
     * migration sets still ran in the default lane at ~4.3s each. Going through
     * freshDatabase copies the migrated template instead, and also remints the controller
     * identity and re-points the datasource-bound services -- three things the hand-rolled
     * shape silently skipped. A class whose SUBJECT is migrating (MigrationIntegrityTest,
     * the two encryption journeys, the two backfills) must keep migrating from empty.
     *
     * @return the datasource registered as {@code Datasources.DEFAULT}
     */
    public static synchronized SqlDatasource freshDatasource() throws Exception {
        freshDatabase();
        return HohenheimDatabase.datasource();
    }

    /** {@link #freshDatasource()}, then the runtime booted over it. */
    public static synchronized SqlDatasource freshBootedDatasource() throws Exception {
        boolean alreadyBooted = ServerZenitRuntime.INSTANCE != null;
        SqlDatasource datasource = freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        if (alreadyBooted) {
            // Runtime boot is process-wide, but its SEED stage belongs to each new database.
            Seeds.runDiscovered();
        }
        return datasource;
    }

    /**
     * Release the connection pool of the database this swap just abandoned.
     *
     * AIDEV-NOTE: `Datasources.register` only replaces a map entry, so before this the
     * outgoing SqliteDatasource kept its pool (MAX_IDLE_CONNECTIONS = 8) plus a WAL and a
     * shm file alive until JVM exit. That was harmless while every class owned a JVM that
     * died seconds later; sharing a JVM across ~30 classes turned it into hundreds of live
     * descriptors per fork, and at six forks the accept loop in PublicTcpListenerRecoveryTest
     * took a real ENFILE ("Too many open files in system") on a box whose fd limits are
     * effectively unlimited. Closing on swap removes the leak instead of hiding it behind a
     * lower fork count. Failure is swallowed: losing the release can only make the suite
     * heavier, never wrong, and a close that throws must not fail the test that triggered it.
     */
    private static void closeOutgoing(SqlDatasource outgoing) {
        if (outgoing == null || outgoing == HohenheimDatabase.datasource()) {
            return;
        }
        try {
            outgoing.close();
        } catch (Exception ignored) {
            // Best effort; see the note above.
        }
    }

    /**
     * Re-point the datasource-bound services at the database {@code HohenheimDatabase} serves now, for a class that
     * opened its own file (an upgrade fixture) instead of a fresh copy.
     *
     * AIDEV-NOTE: without it such a class passes only as the first in its JVM: zenit-auth kept answering from the
     * database of the class before.
     */
    public static synchronized void adoptCurrentDatabase() {
        rebindDatasourceBoundServices();
    }

    /**
     * Re-point the process-global services that CAPTURED the outgoing datasource.
     *
     * AIDEV-NOTE: this is what makes a shared-JVM lane possible. `Datasources.register` and
     * `Models.get(X)` resolve the default per call, so ordinary ORM access follows a swap by
     * itself -- but two consumers bind a datasource ONCE and keep it forever, and both go
     * silently wrong rather than failing:
     *   - zenit-auth: ZenitAuth.initForTests binds every auth model to one datasource INSTANCE
     *     (AuthModels.bind) and rebuilds the services over them. After a swap, the user, grant
     *     and session models answer from the PREVIOUS database until that rebind, so a seeded
     *     admin is invisible and a minted session stops resolving.
     *   - the task service: TaskService captures SystemTaskModel/SystemTaskHistoryModel/
     *     TaskClaimManager at construction, so every later cron tick claims and writes
     *     history into the abandoned file.
     * The task service is deliberately STOPPED rather than restarted: TaskService.initialize()
     * reconciles and boot-fires (~1s), 11 classes swap the database 84 times between them, and
     * the cron ticks are themselves a documented cross-class hazard (HohenheimTestRuntime's
     * note on SuperviseProxyListeners mutating the counters a proxy test is asserting).
     * A class that needs live scheduling starts its own service or declares itself solo.
     */
    private static void rebindDatasourceBoundServices() {
        ZenitAuth.initForTests(HohenheimDatabase.datasource());
        TaskRuntime.stop();
        // Stopping takes the task models back; a booted host always has them registered before MODULES (a panel's
        // source walk resolves every entry's model, the task schedules' included), so they come back on the new
        // database, the service still stopped.
        TaskRuntime.registerModels(HohenheimDatabase.datasource());
    }

    /**
     * A copied database must NOT inherit the template's controller identity.
     *
     * AIDEV-NOTE: the whole point of the identity is that one database is one controller,
     * and every daemon resource name is namespaced by it. A file copy would hand several
     * "controllers" the same token while their record ids both restart at 1 -- exactly
     * the collision the namespace exists to remove. Dropping the row makes the next
     * resolve mint a fresh one.
     *
     * AIDEV-NOTE: that fresh token is ALSO the name every Docker network this database's
     * workloads will get, so it is recorded with {@link LiveNamespaces} here -- this is
     * the one place that knows a namespace was born, and a namespace nobody recorded is
     * one nobody can reap after a worker JVM is killed mid-run.
     */
    private static void remintControllerIdentity() {
        Db.run(HohenheimDatabase.datasource(), () ->
            Models.get(ControllerIdentityModel.class).find().all()
                .forEach(row -> Models.get(ControllerIdentityModel.class)
                    .delete(row.get(ControllerIdentityModel.ID))));
        ControllerIdentity.forgetForTest();
        ControllerIdentity.resolve();
        LiveNamespaces.note(ControllerIdentity.token());
    }
}
