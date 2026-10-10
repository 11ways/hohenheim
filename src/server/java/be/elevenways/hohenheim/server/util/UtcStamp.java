package be.elevenways.hohenheim.server.util;

import be.elevenways.protoblast.common.time.Now;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * THE {@code yyyyMMdd-HHmmss} UTC stamp that names backups, snapshots, captures and migration artifacts.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class UtcStamp {

    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private UtcStamp() {
    }

    /** @return the current instant ({@link Now}) as a stamp */
    public static @NonNull String now() {
        return FORMAT.format(Now.instant());
    }
}
