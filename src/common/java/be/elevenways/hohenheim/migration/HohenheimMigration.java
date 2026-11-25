package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.Migration;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Base for this module's migrations: ONE declared version stream, so out-of-order
 * drift detection can never fragment from package placement.
 *
 * AIDEV-NOTE: zenit chains every stream in version order (a115be18), so a migration declares
 * only its cross-stream edges, never its predecessor. The stream starts with ONE baseline, M001_HohenheimBaseline,
 * which squashed 001..019 on 2026-10-21 and is published (kuifje, robbedoes). The last published version is
 * M008_StreamPortLists, which starfleet, robbedoes and kuifje applied on 2026-11-25; M001 to M008 are all applied on
 * the live hosts, so no migration is open and a schema change is a NEW migration, the one open migration until the
 * next live deploy publishes it. MigrationIntegrityTest pins the list and that a migration at or below
 * MigrationIntegrityTest.DEPLOYED_THROUGH is never edited: a deploy that applies the open migration raises PUBLISHED
 * and pins it in the same commit, or the open migration stays editable while installs already recorded it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public abstract class HohenheimMigration extends Migration {

    /** The version-stream token every migration of this module declares. */
    @NonNull
    public static final String STREAM = "be.elevenways.hohenheim";

    /** The version Hohenheim's live hosts recorded last (starfleet, robbedoes, kuifje, 2026-11-25 about 06:15). */
    @NonNull
    public static final String PUBLISHED = "2026_11_25_000001";

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
