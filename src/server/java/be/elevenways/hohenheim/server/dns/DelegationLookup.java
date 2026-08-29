package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.xbill.DNS.Name;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * How a delegation check finds the servers it must ask: the parent zone's nameservers and
 * the addresses behind a delegated NS name that came without glue. Production resolves
 * through the system resolver ({@link SystemDelegationLookup}); a test points both at
 * loopback listeners.
 */
public interface DelegationLookup {

    /**
     * @return the parent zone's authoritative nameservers for {@code zone}, empty when the
     *         parent could not be found
     */
    @NonNull List<InetSocketAddress> parentNameservers(@NonNull Name zone) throws Exception;

    /** @return the addresses a delegated nameserver name resolves to, empty when it does not */
    @NonNull List<InetSocketAddress> addressesOf(@NonNull Name nameserver) throws Exception;

    /** @return the port a glue address is queried on */
    int nameserverPort();
}
