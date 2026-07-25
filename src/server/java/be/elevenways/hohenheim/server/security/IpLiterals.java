package be.elevenways.hohenheim.server.security;

import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import be.elevenways.hohenheim.security.IpAddressSyntax;

/**
 * Strict literal IPv4/IPv6 parsing and CIDR matching with NO DNS resolution:
 * untrusted "ip" strings reach the ban paths, and {@code InetAddress.getByName}
 * on a hostname would trigger a lookup, so everything here works on characters only.
 */
public final class IpLiterals {

    // Warn about malformed allowlist entries once per distinct raw value.
    private static volatile @Nullable String lastWarnedList = null;

    private IpLiterals() {
    }

    /** Whether the value is a literal IPv4 or IPv6 address (no DNS, no zone ids). */
    public static boolean isLiteral(@Nullable String value) {
        return parse(value) != null;
    }

    /**
     * @return the address bytes (4 for IPv4, 16 for IPv6), or null when the
     *         value is not a strict literal
     */
    public static byte @Nullable [] parse(@Nullable String value) {
        return IpAddressSyntax.parse(value);
    }

    /** The IPv6 actor identity: the whole /64 network a single actor controls. */
    public static final int V6_SUBNET_PREFIX = 64;

    /**
     * The ban/scoring key of a literal address: IPv4 stays the exact
     * (canonical) address, IPv6 collapses to its /64 network in
     * {@code <network>/64} CIDR form (one v6 actor controls the whole /64).
     *
     * @return the key, or null when the value is not a literal address
     */
    public static @Nullable String subnetKey(@Nullable String value) {
        byte[] bytes = parse(value);
        if (bytes == null) {
            return null;
        }
        if (bytes.length == 4) {
            return formatV4(bytes);
        }
        return formatV6Subnet(bytes);
    }

    /** Canonical dotted-quad text of 4 address bytes. */
    public static @NonNull String formatV4(byte @NonNull [] bytes) {
        return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "."
            + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
    }

    /**
     * The {@code <network>/64} CIDR string of a v6 address's /64: the four head
     * groups in unpadded lowercase hex followed by {@code ::} (the host half is
     * zero by construction, so the trailing {@code ::} is always valid).
     */
    public static @NonNull String formatV6Subnet(byte @NonNull [] bytes) {
        StringBuilder out = new StringBuilder();
        boolean allZero = true;
        for (int group = 0; group < 4; group++) {
            int value = ((bytes[group * 2] & 0xFF) << 8) | (bytes[group * 2 + 1] & 0xFF);
            if (value != 0) {
                allZero = false;
            }
            if (group > 0) {
                out.append(':');
            }
            out.append(Integer.toHexString(value));
        }
        if (allZero) {
            return "::/" + V6_SUBNET_PREFIX;
        }
        return out.append("::/").append(V6_SUBNET_PREFIX).toString();
    }

    /**
     * Whether the address matches any IP or CIDR entry in the structured list.
     */
    public static boolean matchesList(byte @NonNull [] address, @Nullable List<String> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        String rawList = list.toString();
        for (String raw : list) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (matchesEntry(address, entry, rawList)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any v6 entry of the list overlaps the given /64 network: an
     * address inside it, a wider range containing it, or a narrower range
     * within it (a protected address inside the /64 vetoes the whole range).
     */
    public static boolean listOverlapsV6Subnet(byte @NonNull [] network,
                                                @Nullable List<String> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (String raw : list) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String addressPart = entry;
            int prefix = 128;
            int slash = entry.indexOf('/');
            if (slash >= 0) {
                addressPart = entry.substring(0, slash);
                try {
                    prefix = Integer.parseInt(entry.substring(slash + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            byte[] ruleBytes = parse(addressPart);
            if (ruleBytes == null || ruleBytes.length != 16 || prefix < 0 || prefix > 128) {
                continue;
            }
            if (prefixMatches(network, ruleBytes, Math.min(prefix, V6_SUBNET_PREFIX))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesEntry(byte @NonNull [] address, @NonNull String entry,
                                        @NonNull String rawList) {
        String addressPart = entry;
        int prefix = -1;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            addressPart = entry.substring(0, slash);
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1));
            } catch (NumberFormatException e) {
                warnMalformed(entry, rawList);
                return false;
            }
        }
        byte[] ruleBytes = parse(addressPart);
        if (ruleBytes == null) {
            if (slash >= 0) {
                warnMalformed(entry, rawList);
            }
            // No slash and not a literal: a hostname entry, handled by the
            // background resolver (NeverBanHostnames), never matched here.
            return false;
        }
        int bits = ruleBytes.length * 8;
        if (prefix < 0) {
            prefix = bits;
        }
        if (prefix > bits) {
            warnMalformed(entry, rawList);
            return false;
        }
        if (ruleBytes.length != address.length) {
            return false;
        }
        return prefixMatches(address, ruleBytes, prefix);
    }

    private static boolean prefixMatches(byte[] address, byte[] rule, int prefix) {
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != rule[i]) {
                return false;
            }
        }
        int remainder = prefix % 8;
        if (remainder == 0) {
            return true;
        }
        int mask = (0xFF00 >> remainder) & 0xFF;
        return (address[fullBytes] & mask) == (rule[fullBytes] & mask);
    }

    private static void warnMalformed(@NonNull String entry, @NonNull String rawList) {
        if (!rawList.equals(lastWarnedList)) {
            lastWarnedList = rawList;
            Blast.log("SECURITY: ignoring malformed IP/CIDR entry", entry,
                "in allowlist value", rawList);
        }
    }

}
