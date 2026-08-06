package be.elevenways.hohenheim.server.security;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

    /**
     * Whether a literal address falls inside the denied vocabulary, so a consumer that must
     * decide "is THIS address one a tenant would be cut off from" (the process tier's
     * resolver carve-out) asks the vocabulary instead of re-listing the ranges.
     *
     * AIDEV-NOTE: literals only -- resolution is explicitly refused. This answers questions
     * about addresses read out of host configuration (resolv.conf), and a hostname there
     * would otherwise be resolved by the very resolver we are deciding about.
     *
     * @param literal an IPv4 or IPv6 address literal, or null
     * @return false for anything that is not a literal address
     */
    public static boolean denies(@Nullable String literal) {
        InetAddress address = parseLiteral(literal);
        return address != null && denies(address);
    }

    /** @return whether the address falls inside one of the denied ranges */
    public static boolean denies(@NonNull InetAddress address) {
        List<String> ranges = address instanceof Inet4Address ? DENIED_V4 : DENIED_V6;
        for (String range : ranges) {
            if (contains(range, address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param literal an address literal, or null
     * @return the parsed address, or null when the text is not a literal of either family
     */
    public static @Nullable InetAddress parseLiteral(@Nullable String literal) {
        if (literal == null || literal.isBlank()) {
            return null;
        }
        String text = literal.trim();
        // A scope suffix (fe80::1%eth0) is how a link-local resolver is written; the zone
        // is a local interface selector, never part of the address the rule matches on.
        int zone = text.indexOf('%');
        if (zone > 0) {
            text = text.substring(0, zone);
        }
        // getByName resolves HOSTNAMES; only a literal may reach it, so reject anything
        // that is not one before asking.
        if (!text.contains(":") && !text.matches("[0-9.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(text);
        } catch (UnknownHostException notALiteral) {
            return null;
        }
    }

    /** @return whether a {@code address/prefix} range contains the address */
    private static boolean contains(@NonNull String range, @NonNull InetAddress address) {
        int slash = range.indexOf('/');
        InetAddress base = parseLiteral(slash < 0 ? range : range.substring(0, slash));
        if (base == null || base.getClass() != address.getClass()) {
            return false;
        }
        int prefix = slash < 0 ? base.getAddress().length * 8
            : Integer.parseInt(range.substring(slash + 1));
        byte[] baseBytes = base.getAddress();
        byte[] bytes = address.getAddress();
        for (int bit = 0; bit < prefix; bit++) {
            int mask = 0x80 >> (bit % 8);
            if ((baseBytes[bit / 8] & mask) != (bytes[bit / 8] & mask)) {
                return false;
            }
        }
        return true;
    }

    private TenantNetworkRanges() {}
}
