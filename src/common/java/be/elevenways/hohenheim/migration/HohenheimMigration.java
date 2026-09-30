package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.Migration;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Base for this module's migrations: ONE declared version stream, so out-of-order
 * drift detection can never fragment from package placement.
 *
 * AIDEV-NOTE: zenit chains every stream in version order (a115be18), so a migration declares
 * only its cross-stream edges, never its predecessor. MigrationIntegrityTest pins that this
 * stream's execution order is its version order and upgrades an install from every
 * production version. A change no production install has applied edits the newest migration
 * (see MigrationIntegrityTest.DEPLOYED_THROUGH) instead of appending one.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public abstract class HohenheimMigration extends Migration {

    /** The version-stream token every migration of this module declares. */
    @NonNull
    public static final String STREAM = "be.elevenways.hohenheim";

    protected HohenheimMigration(@NonNull String version, @NonNull String name) {
        super(version, name);
    }

    protected HohenheimMigration(@NonNull String version, @NonNull String name, boolean requiresTransaction) {
        super(version, name, requiresTransaction);
    }

    @Override
    @NonNull
    public final String getVersionStream() {
        return STREAM;
    }
}
