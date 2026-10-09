package be.elevenways.hohenheim.instance;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One published port of an instance, joined to the host address it is reachable at.
 *
 * {@code address} is blank when the host record declares no public IP: the surface then
 * shows the port with its {@code note} rather than inventing {@code localhost}, which would
 * be a reachable-looking lie on a remote host.
 *
 * @param stateKey     what the port is to an operator, as a stable hook
 * @param state        the same, in words
 * @param preallocated a RESERVED number that survives a stop (DNS may point at it),
 *                     as opposed to an ephemeral observation of the running workload
 * @param note         why a port without an address reaches only so far, worded for its reader (a tenant reads no
 *                     host internals); null when it has an address
 */
@HawkeyeClass
public record InstanceEndpointView(
    @NonNull String address,
    int port,
    @NonNull String protocol,
    @NonNull String stateKey,
    @NonNull Microcopy state,
    boolean preallocated,
    @Nullable Microcopy note
) {
}
