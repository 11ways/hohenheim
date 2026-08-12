package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.backup.SnapshotCapableDatasource;
import be.elevenways.zenit.server.task.TaskRuntime;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Hands out freshly-migrated databases to tests without paying for the migrations
 * every time.
 *
 * <p>AIDEV-NOTE: {@link HohenheimDatabase#init()} always runs the full migration set,
 * which on an empty SQLite file is roughly four seconds -- the dominant cost of the
 * browser suite, since dozens of tests wanted their own clean database. The FIRST
 * database of a JVM pays that price and is then kept as a template; every later
 * request copies the template file, so {@code migrate()} finds its history table
 * already populated and no-ops. Semantics are identical: callers still get a private,
 * fully-migrated, data-free database.
 *
 * <p>The template is captured through {@link SnapshotCapableDatasource#snapshotTo}
 * (VACUUM INTO), never a bare file copy: the datasource runs journal_mode=WAL, so the
 * main file of a live database is just the header page and a {@code Files.copy} of it
 * captures an EMPTY database -- which is exactly how this optimization was silently dead
 * (every fork re-ran the full migration set) from the WAL default until 2026-08-12.
 */
public final class TestDatabases {

    private static Path template;

    private TestDatabases() {
    }

    /**
     * Where forks of one gradle invocation share a migrated template, or null outside gradle.
     *
     * AIDEV-NOTE: with forkEvery = 1 the in-JVM template never pays off -- every isolated
     * class is the "first database of a JVM" and re-runs the full migration set (~4s x ~70
     * booting forks per default-lane run). Each test task wipes its own directory in
     * doFirst, so a template can never outlive the invocation that migrated it and a
     * migration change cannot leak in from a previous run.
     */
    private static Path sharedTemplateDir() {
        String dir = System.getProperty("hohenheim.testdb.template.dir");
        return dir == null || dir.isBlank() ? null : Path.of(dir);
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

        if (template == null) {
            Path shared = sharedTemplateDir();
            if (shared != null && Files.isRegularFile(shared.resolve("template.db"))) {
                template = shared.resolve("template.db");
            }
        }
        if (template != null) {
            Files.copy(template, db.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        SqlDatasource outgoing = HohenheimDatabase.datasource();

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();
        closeOutgoing(outgoing);
        remintControllerIdentity();
        rebindDatasourceBoundServices();

        // proxy.force_https defaults ON and now fails CLOSED (503) whenever no certificate
        // is loaded -- which is every cleartext test proxy. The test baseline turns it off;
        // the force-SSL availability tests re-enable it explicitly.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FORCE_HTTPS, false);

        if (template == null) {
            captureTemplate(db);
        }

        return db;
    }

    /**
     * The registered default datasource, freshly migrated and private to the caller.
     *
     * AIDEV-NOTE: this exists to kill a copy-pasted fixture. 82 classes hand-rolled
     * "temp file -> new SqliteDatasource -> new MigrationRunner(ds).migrate() ->
     * Datasources.register(DEFAULT, ds)", which bypasses the template entirely (and made
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
     * Re-point the process-global services that CAPTURED the outgoing datasource.
     *
     * AIDEV-NOTE: this is what makes a shared-JVM lane possible. `Datasources.register` and
     * `Models.get(X)` resolve the default per call, so ordinary ORM access follows a swap by
     * itself -- but two consumers bind a datasource ONCE and keep it forever, and both go
     * silently wrong rather than failing:
     *   - zenit-auth: AuthModels.datasource plus the session store, permission resolver and
     *     capability checkers built over it. After a swap, users()/grants()/sessions() answer
     *     from the PREVIOUS database, so a seeded admin is invisible and a minted session
     *     stops resolving. ZenitAuth.initForTests exists for exactly this and had no callers.
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
    }

    /**
     * A copied database must NOT inherit the template's controller identity.
     *
     * AIDEV-NOTE: the whole point of the identity is that one database is one controller,
     * and every daemon resource name is namespaced by it. A file copy would hand several
     * "controllers" the same token while their record ids both restart at 1 -- exactly
     * the collision the namespace exists to remove. Dropping the row makes the next
     * resolve mint a fresh one.
     */
    private static void remintControllerIdentity() {
        Db.run(HohenheimDatabase.datasource(), () ->
            Models.get(ControllerIdentityModel.class).find().all()
                .forEach(row -> Models.get(ControllerIdentityModel.class)
                    .delete(row.get(ControllerIdentityModel.ID))));
        ControllerIdentity.forgetForTest();
        ControllerIdentity.resolve();
    }

    /**
     * Keep the first migrated database as the template every later copy starts from.
     * A failure here is deliberately swallowed: losing the fast path only makes the
     * suite slow again, never wrong.
     */
    private static void captureTemplate(File migrated) {
        try {
            Path candidate = Files.createTempFile("hohenheim-template", ".db");
            if (HohenheimDatabase.datasource() instanceof SnapshotCapableDatasource snapshotable) {
                snapshotable.snapshotTo(candidate);
            } else {
                Files.copy(migrated.toPath(), candidate, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(candidate) <= 4096) {
                // A header-page-only "template" is the WAL trap again: refuse it so the
                // fast path can only ever hand out genuinely migrated databases.
                Files.deleteIfExists(candidate);
                return;
            }
            candidate.toFile().deleteOnExit();
            template = candidate;
            publishSharedTemplate(candidate);
        } catch (Exception ignored) {
            // No template: every caller keeps running its own migrations, as before.
        }
    }

    /**
     * Offer this JVM's template to sibling forks; losing the publish race is fine.
     */
    private static void publishSharedTemplate(Path candidate) {
        Path shared = sharedTemplateDir();
        if (shared == null) {
            return;
        }
        try {
            Files.createDirectories(shared);
            Path staging = Files.createTempFile(shared, "staging-", ".db");
            Files.copy(candidate, staging, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(staging, shared.resolve("template.db"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception raceLost) {
                Files.deleteIfExists(staging);
            }
        } catch (Exception ignored) {
            // Sibling forks keep migrating for themselves, exactly as before.
        }
    }
}
