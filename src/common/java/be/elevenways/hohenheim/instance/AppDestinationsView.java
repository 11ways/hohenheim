package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The Network tab's "Where it sends data" card: the app's destinations over the span, the most sent first.
 *
 * @param recording whether Hohenheim records destinations at all (the Traffic setting); false draws why not
 * @param rows      the listed destinations
 * @param other     what every destination not listed moved together, null when every one is listed
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass
public record AppDestinationsView(boolean recording, @NonNull List<Row> rows, @Nullable Row other) {

    /**
     * One destination, or every one not listed.
     *
     * @param key         unique in the card: the destination's key, "other" for the rest
     * @param name        what it is: the record it names, or its kind's own words, or its address
     * @param note        the line under the name saying what kind of destination it is
     * @param internal    whether its traffic never leaves the server
     * @param port        its port and protocol, empty for the rest
     * @param connections the connections opened to it
     * @param lastSeen    the last minute its counters grew, empty for the rest
     */
    @HawkeyeClass
    public record Row(@NonNull String key, @NonNull Microcopy name, @NonNull Microcopy note, boolean internal,
                      @NonNull String address, @NonNull String port, @NonNull Microcopy sent,
                      @NonNull Microcopy received, @NonNull Microcopy connections, @NonNull Microcopy lastSeen) {
    }
}
