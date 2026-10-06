package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Duration;

/**
 * How a dedicated thread waits out a backoff or a poll interval, injectable so a test records the wait instead.
 *
 * @author Jelle De Loecker
 * @since  0.3.0
 */
@FunctionalInterface
public interface Pause {

    /** Sleeps the calling thread. */
    Pause SLEEP = Thread::sleep;

    /** @throws InterruptedException when the waiting thread is interrupted */
    void pause(@NonNull Duration delay) throws InterruptedException;
}
