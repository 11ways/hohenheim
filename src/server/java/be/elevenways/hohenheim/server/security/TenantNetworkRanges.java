package be.elevenways.hohenheim.server.security;

import java.util.List;

/**
 * THE vocabulary of address ranges a tenant workload may not reach, shared by every
 * enforcement backend (the nftables applier for Docker networks, the Incus network ACL
 * for system containers) so the two can never drift apart.
 *
 * AIDEV-NOTE: the metadata range is listed FIRST and separately named because it is the
 * classic escape: 169.254.169.254 hands out credentials for the whole host on clouds,
 * and its IPv6 twin (fd00:ec2::254) sits inside the ULA range fc00::/7 which is denied
 * wholesale. The RFC1918 ranges cover every OTHER bridge on the same daemon (Docker's
 * default pools and Incus's managed bridges all allocate inside them), which is what
 * makes one static rule set per-workload isolation instead of per-network bookkeeping.
 */
public final class TenantNetworkRanges {

    /** The cloud instance-metadata range: credentials for the whole host, one HTTP GET away. */
    public static final String METADATA_V4 = "169.254.0.0/16";

    /** RFC1918 plus the metadata range: everything a tenant workload may not reach over v4. */
    public static final List<String> DENIED_V4 =
        List.of(METADATA_V4, "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    /** ULA (container networks AND the v6 metadata address) and link-local. */
    public static final List<String> DENIED_V6 = List.of("fc00::/7", "fe80::/10");

    private TenantNetworkRanges() {}
}
