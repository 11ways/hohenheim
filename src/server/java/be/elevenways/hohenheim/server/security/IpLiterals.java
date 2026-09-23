package be.elevenways.hohenheim.server.security;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.net.IpRanges;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Hohenheim's ban-and-allowlist vocabulary over zenit's {@link IpRanges}: the actor key of an
 * address, allowlist matching and the canonical text of an address or range, with NO DNS
 * resolution anywhere.
 *
 * AIDEV-NOTE: the parsing and the CIDR math are zenit's ({@link IpRanges#parseLiteral},
 * {@link IpRanges.Range}); this class only adds what bans need on top. Two strictness rules are
 * kept from the parser this replaced, because untrusted "ip" strings reach the ban paths: the
 * value is trimmed, and a zone id ({@code fe80::1%eth0}) is REFUSED rather than stripped. One
 * rule changed on purpose: an IPv4-mapped literal ({@code ::ffff:203.0.113.5}) now folds to its
 * IPv4 address, where it used to key as the IPv6 network {@code ::/64} and be refused as
 * loopback, so a mapped client is banned as the IPv4 actor it is.
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
     * @return the address bytes (4 for IPv4, 16 for IPv6), or null when the value is not a
     *         strict literal
     */
    public static byte @Nullable [] parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.indexOf('%') >= 0) {
            return null;
        }
        return IpRanges.parseLiteral(trimmed);
    }

    /** The IPv6 actor identity: the whole /64 network a single actor controls. */
    public static final int V6_SUBNET_PREFIX = 64;

    /**
     * The ban/scoring key of a literal address: IPv4 stays the exact (canonical) address,
     * IPv6 collapses to its /64 network in {@code <network>/64} CIDR form (one v6 actor
     * controls the whole /64).
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
     * The {@code <network>/64} CIDR string of a v6 address's /64: the four head groups in
     * unpadded lowercase hex followed by {@code ::} (the host half is zero by construction,
     * so the trailing {@code ::} is always valid).
     *
     * AIDEV-NOTE: this spelling is the STORED key of every v6 ban row, so it must never
     * change -- it is deliberately not the RFC 5952 form {@link #format} produces (which would
     * compress an inner zero run differently, e.g. {@code 2001:0:0:1::/64}).
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
     * The canonical text of an address: dotted quad for IPv4, RFC 5952 for IPv6 (lowercase,
     * unpadded groups, the longest run of two or more zero groups compressed, the first on a
     * tie) -- the spelling nft and Incus render back, which a read-back comparison needs.
     */
    public static @NonNull String format(byte @NonNull [] bytes) {
        if (bytes.length == 4) {
            return formatV4(bytes);
        }
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int start = i;
            while (i < 8 && groups[i] == 0) {
                i++;
            }
            if (i - start > bestLength) {
                bestStart = start;
                bestLength = i - start;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                out.append("::");
                i += bestLength - 1;
                continue;
            }
            if (out.length() > 0 && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[i]));
        }
        return out.toString();
    }

    /** The {@code <address>/<prefix>} text of a range, in the {@link #format} spelling. */
    public static @NonNull String cidr(IpRanges.@NonNull Range range) {
        return format(range.network()) + "/" + range.prefixLength();
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
     * Whether any v6 entry of the list overlaps the given /64 network: an address inside it,
     * a wider range containing it, or a narrower range within it (a protected address inside
     * the /64 vetoes the whole range).
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
            // Both directions of overlap reduce to one test: the entry, cut to at most /64,
            // contains the /64's network address.
            if (new IpRanges.Range(ruleBytes, Math.min(prefix, V6_SUBNET_PREFIX)).matches(network)) {
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
            // No slash and not a literal: a hostname entry, handled by the background
            // resolver (NeverBanHostnames), never matched here.
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
        return new IpRanges.Range(ruleBytes, prefix).matches(address);
    }

    private static void warnMalformed(@NonNull String entry, @NonNull String rawList) {
        if (!rawList.equals(lastWarnedList)) {
            lastWarnedList = rawList;
            Blast.log("SECURITY: ignoring malformed IP/CIDR entry", entry,
                "in allowlist value", rawList);
        }
    }

}
