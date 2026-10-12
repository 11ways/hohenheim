package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A live feed of one workload's {@link StatsReading}s, followed until the consumer closes it or the workload stops.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public interface StatsFeed extends AutoCloseable {

    /**
     * Blocks until the next reading.
     *
     * @return the reading, or null once the feed is over (closed, the workload stopped, the transport ended)
     */
    @Nullable StatsReading next();

    /** Ends the feed; a blocked {@link #next} returns null. Closing twice is a no-op. */
    @Override void close();
}
