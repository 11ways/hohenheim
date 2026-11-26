package be.elevenways.hohenheim.traffic;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a live Activity picture is opened with: what its link asks the server for, and the names of the sites it may
 * show, which the server read for its viewer.
 *
 * @param open     the live channel's open data ({@link ActivityAsk}): every site, one site, or a TV link's token
 * @param order    the sources the viewer may see, their sites' names in alphabetical order
 * @param names    each of those sources' names
 * @param unserved the source of the names no site serves
 * @param visitors whether the picture shows visitors' addresses (the frames then carry them)
 * @param strip    whether the last minute's bars stand under the flying dots (a wall screen)
 * @param legend   every status class's words and colour ({@link ActivityPalette#legend})
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record ActivityView(@NonNull String open, @NonNull List<String> order, @NonNull Map<String, String> names,
                           @NonNull String unserved, boolean visitors, boolean strip,
                           @NonNull List<ActivityPalette.Entry> legend) {

    public ActivityView {
        Objects.requireNonNull(open, "open cannot be null");
        order = List.copyOf(order);
        names = Map.copyOf(names);
        Objects.requireNonNull(unserved, "unserved cannot be null");
        legend = List.copyOf(legend);
    }
}
