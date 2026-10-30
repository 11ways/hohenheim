package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.async.AsyncFailures;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * A managed-database dump refused for its size: over {@code database.max_dump_mb}, or more than its disk has room for.
 *
 * AIDEV-NOTE: typed so a reader that gives the operator words (the BACKUP_FAILED alert) reads {@link #words()}, where
 * the sizes are values worded through :bytes in the reader's locale. The message stays the technical English the log,
 * a stored failure reason and the tests key on, and it names no size: a size is worded for its reader, never spelled
 * into text by hand. The number it does name is the setting's own value to raise it to.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DumpOverCap extends IOException {

    private final transient Microcopy words;

    private DumpOverCap(@NonNull String message, @NonNull Microcopy words, @Nullable Throwable cause) {
        super(message, cause);
        this.words = words;
    }

    /**
     * A dump sized before any byte was transferred.
     *
     * @param dumpBytes the dump's size
     * @param capBytes  the cap it exceeds
     */
    static @NonNull DumpOverCap sized(@NonNull String handle, long dumpBytes, long capBytes) {
        long needed = Math.ceilDiv(dumpBytes, ByteUnit.MEGABYTE.factor());
        return new DumpOverCap("Dump of '" + handle + "' is over the configured cap (setting database.max_dump_mb);"
            + " set database.max_dump_mb to at least " + needed + " (nothing was transferred)",
            HohenheimMicrocopy.ALERT.of("dump_over_cap").withArg("size", dumpBytes).withArg("cap", capBytes), null);
    }

    /**
     * A dump cut off at the cap while it streamed, its whole size unknown.
     *
     * @param capBytes the cap it grew past
     */
    static @NonNull DumpOverCap cutOff(@NonNull String handle, long capBytes, @NonNull Throwable cause) {
        return new DumpOverCap("Dump of '" + handle + "' exceeds the configured cap (setting database.max_dump_mb);"
            + " the partial file was deleted", HohenheimMicrocopy.ALERT.of("dump_cut_off").withArg("cap", capBytes),
            cause);
    }

    /**
     * A dump refused before it started because its staging disk has too little room for it: the disk would fill, and a
     * full disk takes the engines on it down with the dump.
     *
     * @param freeBytes   what the staging disk has free
     * @param neededBytes what the dump needs there, the reserve kept free included
     */
    static @NonNull DumpOverCap noRoom(@NonNull String name, long freeBytes, long neededBytes) {
        return new DumpOverCap("Dump of database '" + name + "' refused: its staging disk has too little free space"
            + " for it (nothing was written)", HohenheimMicrocopy.ALERT.of("dump_no_room")
            .withArg("free", freeBytes).withArg("needed", neededBytes), null);
    }

    /** @return the refusal in the operator's words, its sizes worded for the reader */
    public @NonNull Microcopy words() {
        return this.words;
    }

    /** @return the words of the refusal a failure is, or was caused by, null for any other failure */
    public static @Nullable Microcopy wordsOf(@Nullable Throwable failure) {
        DumpOverCap refusal = failure == null ? null : AsyncFailures.first(failure, DumpOverCap.class);
        return refusal == null ? null : refusal.words();
    }
}
