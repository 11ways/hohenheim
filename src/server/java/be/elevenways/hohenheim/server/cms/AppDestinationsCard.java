package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.AppDestinationsView;
import be.elevenways.hohenheim.server.traffic.AppDestinations;
import be.elevenways.hohenheim.server.traffic.DestinationKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Network tab's "Where it sends data" card, drawn from the destinations the app's network read found
 * (AppNetworkReads), the same read an assistant's {@code app_network_read top=destinations} makes.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class AppDestinationsCard {

    private AppDestinationsCard() {
    }

    /** @param destinations what the read found; null reads as nothing counted */
    static @NonNull AppDestinationsView view(AppNetworkReads.@Nullable Destinations destinations) {
        List<AppDestinationsView.Row> rows = new ArrayList<>();
        AppDestinationsView.Row other = null;
        if (destinations != null) {
            for (AppNetworkReads.Destination destination : destinations.listed()) {
                AppDestinations.Destination where = destination.where();
                DestinationKind kind = where.kind();
                rows.add(new AppDestinationsView.Row(where.key(), DestinationNames.shown(where, destination.name()),
                    kind.note(), kind.internal(), where.address(), where.port() + "/" + where.protocol(),
                    InstanceNetworkPage.size(destination.moved().bytesSent()),
                    InstanceNetworkPage.size(destination.moved().bytesReceived()),
                    count(destination.moved().connections()), destination.lastSeen() == null
                        ? Microcopy.literal("") : HohenheimMicrocopy.TRAFFIC_SCREEN.of("last_seen")
                        .withArg("at", destination.lastSeen())));
            }
            AppNetworkReads.Moved rest = destinations.other();
            if (rest != null) {
                other = new AppDestinationsView.Row("other",
                    HohenheimMicrocopy.APP_NETWORK.of("app_network_destinations_other"),
                    HohenheimMicrocopy.APP_NETWORK.of("app_network_destinations_other_note"), false, "", "",
                    InstanceNetworkPage.size(rest.bytesSent()), InstanceNetworkPage.size(rest.bytesReceived()),
                    count(rest.connections()),
                    Microcopy.literal(""));
            }
        }
        return new AppDestinationsView(AppDestinations.recording(), rows, other);
    }

    private static @NonNull Microcopy count(long count) {
        return HohenheimMicrocopy.APP_NETWORK.of("app_network_requests").withArg("count", count);
    }
}
