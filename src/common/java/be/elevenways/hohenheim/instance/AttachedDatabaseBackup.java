package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.StateLineCell;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One database attached to an app as its Backups tab names it: the database, its page, and its last backup.
 *
 * @param name       the managed database's name, verbatim
 * @param url        its page for a reader who may open it, null otherwise
 * @param lastBackup its last backup as the Databases list words it, null when that list draws none for it
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record AttachedDatabaseBackup(@NonNull String name, @Nullable String url, @Nullable StateLineCell lastBackup) {
}
