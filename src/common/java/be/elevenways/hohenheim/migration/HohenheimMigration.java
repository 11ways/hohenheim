package be.elevenways.hohenheim.migration;

import be.elevenways.zenit.common.orm.migration.Migration;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Base for this module's migrations: ONE declared version stream, so out-of-order
 * drift detection can never fragment from package placement.
 *
 * AIDEV-NOTE: every migration appended after 012 declares {@code dependsOn(<the previous
 * migration of this stream>)}. zenit runs ONE topological order over every stream, breaking ties by version
 * TEXT, and its strict integrity check refuses a pending migration placed before an applied one
 * of the same stream in that order. This stream's "0xx" versions sort before every zenit
 * timestamp, so a migration with no edge runs at the very front, while 012 (which reads
 * zenit-auth's grant table and depends on its M007) waits behind zenit-auth. Without the chain
 * an appended 015 ran BEFORE 012, and any install that had applied 012 but not 015 was refused
 * as out of order. MigrationIntegrityTest pins that this stream's execution order is its
 * version order and upgrades an install from every intermediate version.
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
