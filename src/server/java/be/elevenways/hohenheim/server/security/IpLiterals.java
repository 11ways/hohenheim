package be.elevenways.hohenheim.server.security;

import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Strict literal IPv4/IPv6 parsing and CIDR matching with NO DNS resolution:
 * ingest feeds untrusted "ip" strings, and {@code InetAddress.getByName} on a
 * hostname would trigger a lookup, so everything here works on characters only.
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
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.indexOf(':') >= 0) {
            return parseV6(trimmed);
        }
        return parseV4(trimmed);
    }

    /**
     * Whether the address matches any entry of a comma/whitespace-separated
     * list of IPs and CIDR ranges. Malformed entries are skipped with a
     * one-per-list-value warning.
     */
    public static boolean matchesList(byte @NonNull [] address, @Nullable String list) {
        if (list == null || list.isBlank()) {
            return false;
        }
        for (String entry : list.split("[,\\s]+")) {
            if (entry.isBlank()) {
                continue;
            }
            if (matchesEntry(address, entry.trim(), list)) {
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
            warnMalformed(entry, rawList);
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

    // -----------------------------------------------------------------------
    // IPv4
    // -----------------------------------------------------------------------

    private static byte @Nullable [] parseV4(@NonNull String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            Integer octet = decimalOctet(parts[i]);
            if (octet == null) {
                return null;
            }
            bytes[i] = (byte) (int) octet;
        }
        return bytes;
    }

    private static @Nullable Integer decimalOctet(@NonNull String part) {
        if (part.isEmpty() || part.length() > 3) {
            return null;
        }
        int value = 0;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
            value = value * 10 + (c - '0');
        }
        return value <= 255 ? value : null;
    }

    // -----------------------------------------------------------------------
    // IPv6
    // -----------------------------------------------------------------------

    private static byte @Nullable [] parseV6(@NonNull String value) {
        if (value.indexOf('%') >= 0) {
            // Zone ids are interface-local and never bannable addresses.
            return null;
        }
        int doubleColon = value.indexOf("::");
        if (doubleColon >= 0 && value.indexOf("::", doubleColon + 1) >= 0) {
            return null;
        }

        String head = doubleColon >= 0 ? value.substring(0, doubleColon) : value;
        String tail = doubleColon >= 0 ? value.substring(doubleColon + 2) : "";

        int[] headGroups = v6Groups(head, doubleColon < 0);
        int[] tailGroups = doubleColon >= 0 ? v6Groups(tail, true) : new int[0];
        if (headGroups == null || tailGroups == null) {
            return null;
        }

        int total = headGroups.length + tailGroups.length;
        if (doubleColon >= 0 ? total > 7 : total != 8) {
            return null;
        }

        byte[] bytes = new byte[16];
        for (int i = 0; i < headGroups.length; i++) {
            bytes[i * 2] = (byte) (headGroups[i] >> 8);
            bytes[i * 2 + 1] = (byte) headGroups[i];
        }
        int offset = 16 - tailGroups.length * 2;
        for (int i = 0; i < tailGroups.length; i++) {
            bytes[offset + i * 2] = (byte) (tailGroups[i] >> 8);
            bytes[offset + i * 2 + 1] = (byte) tailGroups[i];
        }
        return bytes;
    }

    /**
     * @param allowEmbeddedV4 whether the LAST group may be a dotted-quad
     * @return the group values (embedded IPv4 expands to two groups), or null
     */
    private static int @Nullable [] v6Groups(@NonNull String section, boolean allowEmbeddedV4) {
        if (section.isEmpty()) {
            return new int[0];
        }
        String[] parts = section.split(":", -1);
        int[] groups = new int[parts.length + 1];
        int count = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean last = i == parts.length - 1;
            if (last && allowEmbeddedV4 && part.indexOf('.') >= 0) {
                byte[] v4 = parseV4(part);
                if (v4 == null) {
                    return null;
                }
                groups[count++] = ((v4[0] & 0xFF) << 8) | (v4[1] & 0xFF);
                groups[count++] = ((v4[2] & 0xFF) << 8) | (v4[3] & 0xFF);
                continue;
            }
            Integer group = hexGroup(part);
            if (group == null) {
                return null;
            }
            groups[count++] = group;
        }
        return java.util.Arrays.copyOf(groups, count);
    }

    private static @Nullable Integer hexGroup(@NonNull String part) {
        if (part.isEmpty() || part.length() > 4) {
            return null;
        }
        int value = 0;
        for (int i = 0; i < part.length(); i++) {
            int digit = Character.digit(part.charAt(i), 16);
            if (digit < 0) {
                return null;
            }
            value = (value << 4) | digit;
        }
        return value;
    }
}
