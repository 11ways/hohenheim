package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.model.Models;

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
 * <p>The copy is only sound because SQLite keeps everything in one file and the
 * datasource does not enable WAL. If WAL is ever turned on, the {@code -wal}/{@code -shm}
 * sidecars must be checkpointed before {@link #captureTemplate} copies the file.
 */
public final class TestDatabases {

    private static Path template;

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

        if (template != null) {
            Files.copy(template, db.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Database.PATH, db.getAbsolutePath());
        HohenheimDatabase.init();
        remintControllerIdentity();

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
            Files.copy(migrated.toPath(), candidate, StandardCopyOption.REPLACE_EXISTING);
            candidate.toFile().deleteOnExit();
            template = candidate;
        } catch (Exception ignored) {
            // No template: every caller keeps running its own migrations, as before.
        }
    }
}
