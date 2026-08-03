package be.elevenways.hohenheim.server.security;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The addressing facts {@link WorkloadNetworkPolicy} needs about one workload's private
 * network, read back OFF THE DAEMON rather than assumed from what we asked for.
 *
 * AIDEV-NOTE: {@link #fromInspect} is deliberately hostile about IPv6. Hohenheim creates
 * its networks with {@code EnableIPv6: false}, but a daemon can hand back a v6-enabled
 * network anyway (daemon-level default network options, or an operator-created network we
 * adopt), and a v4-only policy on a v6-capable network is a BYPASS, not a partial win: the
 * container simply reaches the metadata address and its neighbours over v6. So a network
 * that reports v6 either yields a v6 subnet the policy can cover, or this refuses to build
 * a policy object at all -- there is no third outcome and no "log and continue".
 *
 * @param name        the Docker network name
 * @param ipv4Subnet  CIDR of the network's v4 subnet, e.g. {@code 172.18.0.0/16}
 * @param ipv4Gateway the bridge gateway address (the host, from inside the container)
 * @param ipv6Subnet  CIDR of the v6 subnet when the network is v6-enabled, else null
 */
public record WorkloadNetwork(@NonNull String name,
                              @NonNull String ipv4Subnet,
                              @NonNull String ipv4Gateway,
                              @Nullable String ipv6Subnet) {

    /**
     * Build the policy input from a {@code /networks/{id}} inspection payload.
     *
     * @throws IOException when the payload carries no usable v4 subnet, or is v6-enabled
     *                     without a v6 subnet the policy could cover
     */
    public static @NonNull WorkloadNetwork fromInspect(@NonNull Map<String, Object> inspect)
            throws IOException {
        String name = inspect.get("Name") instanceof String text ? text : "";
        if (name.isBlank()) {
            throw new IOException("REFUSED to build a network policy: the daemon returned a"
                + " network with no Name");
        }

        String v4Subnet = null;
        String v4Gateway = null;
        String v6Subnet = null;
        Object ipam = inspect.get("IPAM");
        Object configs = ipam instanceof Map<?, ?> map ? map.get("Config") : null;
        if (configs instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> config)) {
                    continue;
                }
                String subnet = config.get("Subnet") instanceof String text ? text : "";
                if (subnet.isBlank()) {
                    continue;
                }
                if (subnet.indexOf(':') >= 0) {
                    if (v6Subnet == null) {
                        v6Subnet = subnet;
                    }
                } else if (v4Subnet == null) {
                    v4Subnet = subnet;
                    v4Gateway = config.get("Gateway") instanceof String text ? text : null;
                }
            }
        }

        if (v4Subnet == null) {
            throw new IOException("REFUSED to build a network policy for '" + name + "': the"
                + " daemon reports no IPv4 subnet, so there is nothing to scope the deny rules"
                + " to and every rule would match nothing");
        }
        if (v4Gateway == null || v4Gateway.isBlank()) {
            throw new IOException("REFUSED to build a network policy for '" + name + "': the"
                + " IPv4 config has no Gateway; the host-facing address is what the input deny"
                + " rule exists for");
        }
        if (Boolean.TRUE.equals(inspect.get("EnableIPv6")) && v6Subnet == null) {
            throw new IOException("REFUSED to build a network policy for '" + name + "': the"
                + " network is IPv6-enabled but reports no IPv6 subnet. A v4-only policy on a"
                + " v6-reachable container is a bypass, not a partial win -- recreate the"
                + " network without IPv6 or give it a v6 subnet the policy can cover.");
        }
        return new WorkloadNetwork(name, v4Subnet, v4Gateway, v6Subnet);
    }
}
