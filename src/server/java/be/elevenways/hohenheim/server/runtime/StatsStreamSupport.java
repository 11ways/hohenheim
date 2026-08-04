package be.elevenways.hohenheim.server.runtime;

import java.io.IOException;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Optional driver capability (the {@link ConsoleStreamSupport} shape): live resource
 * samples for one workload. A driver that lacks it is refused BY NAME by the stats hub,
 * never silently answered with an empty chart.
 *
 * The stream carries the driver's OWN sample encoding -- Docker's NDJSON stats objects
 * here, Incus's metrics later -- and the decoding lives with the consumer that knows the
 * driver, not in this contract.
 */
public interface StatsStreamSupport {

    /**
     * Follow the workload's resource samples until the consumer closes the stream or the
     * workload stops.
     *
     * @throws IOException when the workload is absent, not running, or the transport has
     *                     no streaming lane
     */
    @NonNull ConsoleStream openStats(@NonNull String handle) throws IOException;
}
