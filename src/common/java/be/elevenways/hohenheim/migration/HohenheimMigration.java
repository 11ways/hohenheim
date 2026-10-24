package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.Migration;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Base for this module's migrations: ONE declared version stream, so out-of-order
 * drift detection can never fragment from package placement.
 *
 * AIDEV-NOTE: zenit chains every stream in version order (a115be18), so a migration declares
 * only its cross-stream edges, never its predecessor. The stream starts with ONE baseline, M001_HohenheimBaseline,
 * which squashed 001..019 on 2026-10-21 and is published (kuifje, robbedoes). At most ONE migration follows the last
 * published version (M002_AfterBaseline today): every schema change edits it until the next live deploy publishes
 * it, and a second unpublished migration is never added. MigrationIntegrityTest pins the list and that a migration
 * at or below MigrationIntegrityTest.DEPLOYED_THROUGH is never edited.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public abstract class HohenheimMigration extends Migration {

    /** The version-stream token every migration of this module declares. */
    @NonNull
    public static final String STREAM = "be.elevenways.hohenheim";

    /** The Hohenheim version its live hosts recorded last (kuifje, robbedoes, 2026-10-21); only a live deploy moves it. */
    @NonNull
    public static final String PUBLISHED = "2026_10_21_000001";

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

    @Override
    @NonNull
    public final String getPublishedVersion() {
        return PUBLISHED;
    }
}
