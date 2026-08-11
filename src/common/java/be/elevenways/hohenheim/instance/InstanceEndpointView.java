package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * One published port of an instance, joined to the host address it is reachable at.
 *
 * {@code address} is blank when the host record declares no public IP: the surface then
 * shows the port with an explicit "the host declares no public address" note rather than
 * inventing {@code localhost}, which would be a reachable-looking lie on a remote host.
 *
 * @param preallocated a RESERVED number that survives a stop (DNS may point at it),
 *                     as opposed to an ephemeral observation of the running workload
 */
@HawkeyeClass
public record InstanceEndpointView(
    @NonNull String address,
    int port,
    @NonNull String protocol,
    @NonNull String status,
    boolean preallocated
) {
}
