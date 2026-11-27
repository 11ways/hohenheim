package be.elevenways.hohenheim.traffic;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The live "Apps sending" picture's reading for one viewer: the apps it may see, where they sent data and how much a
 * second, as the last reading of every host's counters found it.
 *
 * @param recording whether Hohenheim records where apps send data (the Traffic setting); false draws why not
 * @param at        when the last reading was taken, null before the first one since Hohenheim started
 * @param seconds   how many seconds the reading covers: the time since the reading before it
 * @param apps      the app rows, the most sent first, then the apps that sent nothing, then every one not listed
 * @param places    the destination rows, the most received first, then every one not listed
 * @param flows     what each app row sent each destination row, the app's order kept
 * @author Jelle De Loecker
 * @since  0.1.0
 */
@HawkeyeClass
public record AppsSendingView(boolean recording, @Nullable Instant at, long seconds, @NonNull List<App> apps,
                              @NonNull List<Place> places, @NonNull List<Flow> flows) {

    public AppsSendingView {
        apps = List.copyOf(apps);
        places = List.copyOf(places);
        flows = List.copyOf(flows);
    }

    /**
     * One app, or every app not listed together.
     *
     * @param key       unique among the rows: the app's id, {@code more} for the rest
     * @param hue       the categorical hue its bands are drawn in (a ColorHue token)
     * @param perSecond what it sent a second over the reading, in bytes
     * @param more      how many apps the row stands for when it is the rest, 0 for one app
     */
    @HawkeyeClass
    public record App(@NonNull String key, @NonNull Microcopy name, @NonNull String hue, long perSecond, int more) {

        public App {
            Objects.requireNonNull(key, "key cannot be null");
            Objects.requireNonNull(name, "name cannot be null");
            Objects.requireNonNull(hue, "hue cannot be null");
        }
    }

    /**
     * One destination, or every one not listed together.
     *
     * @param key    unique among the rows: the destination's key, {@code other} for the rest
     * @param name   what it is, in the viewer's view (DestinationNames)
     * @param detail its address and port, empty for the rest
     */
    @HawkeyeClass
    public record Place(@NonNull String key, @NonNull Microcopy name, @NonNull String detail) {

        public Place {
            Objects.requireNonNull(key, "key cannot be null");
            Objects.requireNonNull(name, "name cannot be null");
            Objects.requireNonNull(detail, "detail cannot be null");
        }
    }

    /**
     * What one app row sent one destination row.
     *
     * @param app       the app row's index
     * @param place     the destination row's index
     * @param perSecond bytes a second over the reading, at least 1 for anything sent
     */
    @HawkeyeClass
    public record Flow(int app, int place, long perSecond) {
    }

    /** @return the reading before any is taken, or with nothing to show */
    public static @NonNull AppsSendingView none(boolean recording) {
        return new AppsSendingView(recording, null, 0, List.of(), List.of(), List.of());
    }
}
