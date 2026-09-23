package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One trust relationship a controller keeps with a host: the declaring home of the trust-lane vocabulary.
 *
 * AIDEV-NOTE: the lane token is ALSO the row-action lane id ServerTrustActions composes its action
 * ids from ({@code host_key} / {@code incus_cert}), so it never changes. The host overview's trust card
 * reads its title and client-material label off the member ({@code HostTrustLanes.of(lane.laneId)}), so
 * no template branches over lane literals; once TrustLaneView carries the member itself the template
 * reads {@code lane.lane.title} and {@link #of} can go.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum HostTrustLane {

    /** The ssh host key the controller pins, and the client key it installs. */
    SSH("host_key",
        Microcopy.of("trust_ssh").withFilter("scope", "server_overview"),
        Microcopy.of("client_key").withFilter("scope", "server_overview")),

    /** The Incus server certificate the controller pins, and the client certificate it presents. */
    INCUS("incus_cert",
        Microcopy.of("trust_incus").withFilter("scope", "server_overview"),
        Microcopy.of("client_cert").withFilter("scope", "server_overview"));

    private final String key;
    private final Microcopy title;
    private final Microcopy clientLabel;

    HostTrustLane(@NonNull String key, @NonNull Microcopy title, @NonNull Microcopy clientLabel) {
        this.key = key;
        this.title = title;
        this.clientLabel = clientLabel;
    }

    /** @return the lane token, also the lane's row-action id */
    public @NonNull String key() {
        return this.key;
    }

    /** @return the lane card's title */
    public @NonNull Microcopy title() {
        return this.title;
    }

    /** @return the name of the client material this controller installs on the host for this lane */
    public @NonNull Microcopy clientLabel() {
        return this.clientLabel;
    }

    /**
     * The member a lane token names.
     *
     * @throws IllegalArgumentException for an unknown token: an unknown lane fails closed instead of
     *         rendering as the other lane
     */
    @HawkeyeFunction(
        name = "of",
        namespace = "HostTrustLanes",
        description = "The host trust lane a lane token names",
        returnType = HostTrustLane.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "key", required = true, type = String.class, expectsReference = false,
                 description = "The lane token a trust lane view carries")
        }
    )
    public static @NonNull HostTrustLane of(@NonNull String key) {
        for (HostTrustLane lane : values()) {
            if (lane.key.equals(key)) {
                return lane;
            }
        }
        throw new IllegalArgumentException("Unknown host trust lane: " + key);
    }
}
