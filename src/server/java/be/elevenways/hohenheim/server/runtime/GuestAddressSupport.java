package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;

/**
 * Optional driver capability (the {@link StatsStreamSupport} shape): the addresses a running guest holds, read from
 * the daemon, for a workload that is an addressable system rather than a published port.
 */
public interface GuestAddressSupport {

    /**
     * One network interface of the guest and its routable addresses (loopback and link-local left out).
     *
     * @param network the daemon network the interface is attached to (the host parent of a NIC on the local
     *                network), or empty when the daemon names none
     * @param primary whether it is the workload's main NIC, the one a guest on the local network is reached on
     */
    record GuestInterface(@NonNull String name, @NonNull String network, @NonNull List<String> addresses,
                          boolean primary) {}

    /**
     * The guest's interfaces as the daemon reports them right now; empty when the workload is not running.
     *
     * @param timeoutMs how long the daemon may take, so a page that asks is never held by an unreachable host
     * @throws IOException when the daemon cannot be asked
     */
    @NonNull List<GuestInterface> guestAddresses(@NonNull String handle, long timeoutMs) throws IOException;
}
