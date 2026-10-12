package be.elevenways.hohenheim.server.runtime;

import java.io.IOException;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Optional driver capability (the {@link ConsoleStreamSupport} shape): live resource
 * readings for one workload. A driver that lacks it is refused BY NAME by the stats hub,
 * never silently answered with an empty chart.
 *
 * AIDEV-NOTE: the DRIVER decodes its own encoding (Docker's NDJSON stats objects in
 * {@link DockerStatsFeed}, Incus's instance state and Prometheus metrics in
 * {@link IncusStatsFeed}) into driver-neutral {@link StatsReading}s: the hub that fans them
 * out knows no driver, so a third driver is one feed and no edit there.
 */
public interface StatsStreamSupport {

    /**
     * Follow the workload's resource readings until the consumer closes the feed or the
     * workload stops.
     *
     * @throws IOException when the workload is absent, not running, or the transport has
     *                     no lane to read it
     */
    @NonNull StatsFeed openStats(@NonNull String handle) throws IOException;
}
