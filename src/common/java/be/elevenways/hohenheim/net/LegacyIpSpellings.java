package be.elevenways.hohenheim.net;

import be.elevenways.zenit.common.net.IpRanges;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The address a value spelled for the retired lax zenit parser meant, so Hohenheim can rewrite what it stored.
 *
 * AIDEV-NOTE: FROZEN at zenit f0306e25, the parser the first production builds shipped. It accepted a zone
 * id (dropped), IPv4 octets with leading zeros (read as DECIMAL), any Unicode hex digit in an IPv6 group
 * and an embedded dotted quad in ANY group (1.2.3.4::1 read as 102:304::1); zenit refuses all of them
 * now. This is that old reading, kept only to canonicalize values stored under it (M011's access rules, the
 * trusted-source and never-ban settings) and never to match an address. Changing it changes what a
 * stored rule means.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class LegacyIpSpellings {

    private LegacyIpSpellings() {
    }

    /**
     * @return the canonical spelling of a network the strict parser refuses but the previous build
     *         read, or null when the value needs no rewrite (strictly valid) or was never readable
     */
    public static @Nullable String canonicalNetwork(@NonNull String stored) {
        String value = stored.trim();
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        if (IpRanges.parseLiteral(address) != null) {
            return null;
        }
        byte[] legacy = legacyAddress(address);
        if (legacy == null) {
            return null;
        }
        if (slash < 0) {
            return IpRanges.format(legacy);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(value.substring(slash + 1).trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
        // The previous Range.of re-expressed a colon spelling folded to IPv4 over its 4 bytes.
        if (legacy.length == 4 && address.indexOf(':') >= 0) {
            prefix -= 96;
        }
        if (prefix < 0 || prefix > legacy.length * 8) {
            return null;
        }
        return IpRanges.format(legacy) + "/" + prefix;
    }

    /**
     * The previous build's reading of a literal: a zone id dropped, decimal octets with leading
     * zeros, any Unicode hex digit in an IPv6 group, a dotted quad in any group, the IPv4-mapped form folded.
     */
    public static byte @Nullable [] legacyAddress(@NonNull String value) {
        if (value.isEmpty()) {
            return null;
        }
        if (value.indexOf(':') < 0) {
            return legacyIpv4(value);
        }
        byte[] parsed = legacyIpv6(value);
        if (parsed == null) {
            return null;
        }
        for (int i = 0; i < 10; i++) {
            if (parsed[i] != 0) {
                return parsed;
            }
        }
        return parsed[10] == (byte) 0xFF && parsed[11] == (byte) 0xFF
            ? new byte[] {parsed[12], parsed[13], parsed[14], parsed[15]} : parsed;
    }

    private static byte @Nullable [] legacyIpv4(@NonNull String value) {
        byte[] bytes = new byte[4];
        int part = 0;
        int digits = 0;
        int accumulator = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (digits == 0 || part == 3) {
                    return null;
                }
                bytes[part++] = (byte) accumulator;
                digits = 0;
                accumulator = 0;
                continue;
            }
            if (c < '0' || c > '9' || ++digits > 3) {
                return null;
            }
            accumulator = (accumulator * 10) + (c - '0');
            if (accumulator > 255) {
                return null;
            }
        }
        if (part != 3 || digits == 0) {
            return null;
        }
        bytes[3] = (byte) accumulator;
        return bytes;
    }

    private static byte @Nullable [] legacyIpv6(@NonNull String raw) {
        int zone = raw.indexOf('%');
        String value = zone >= 0 ? raw.substring(0, zone) : raw;
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 1) >= 0) {
            return null;
        }
        String head = compression < 0 ? value : value.substring(0, compression);
        String tail = compression < 0 ? "" : value.substring(compression + 2);
        List<byte[]> leading = legacyGroups(head);
        List<byte[]> trailing = legacyGroups(tail);
        if (leading == null || trailing == null) {
            return null;
        }
        int total = leading.size() + trailing.size();
        if (compression < 0 ? total != 8 : total > 7) {
            return null;
        }
        byte[] bytes = new byte[16];
        int offset = 0;
        for (byte[] group : leading) {
            bytes[offset++] = group[0];
            bytes[offset++] = group[1];
        }
        offset = 16 - (trailing.size() * 2);
        for (byte[] group : trailing) {
            bytes[offset++] = group[0];
            bytes[offset++] = group[1];
        }
        return bytes;
    }

    private static @Nullable List<byte[]> legacyGroups(@NonNull String half) {
        List<byte[]> groups = new ArrayList<>();
        if (half.isEmpty()) {
            return groups;
        }
        if (half.charAt(0) == ':' || half.charAt(half.length() - 1) == ':') {
            return null;
        }
        for (String group : half.split(":", -1)) {
            if (group.isEmpty()) {
                return null;
            }
            if (group.indexOf('.') >= 0) {
                byte[] embedded = legacyIpv4(group);
                if (embedded == null) {
                    return null;
                }
                groups.add(new byte[] {embedded[0], embedded[1]});
                groups.add(new byte[] {embedded[2], embedded[3]});
                continue;
            }
            if (group.length() > 4) {
                return null;
            }
            int accumulator = 0;
            for (int i = 0; i < group.length(); i++) {
                // Deliberately Character.digit, not the ASCII-only BlastString.hexDigitValue: the frozen
                // parser read fullwidth digits, and AccessRuleNetworkSpellingMigrationTest pins that reading.
                int digit = Character.digit(group.charAt(i), 16);
                if (digit < 0) {
                    return null;
                }
                accumulator = (accumulator << 4) | digit;
            }
            groups.add(new byte[] {(byte) (accumulator >> 8), (byte) accumulator});
        }
        return groups;
    }

    /**
     * The canonical spelling of a stored network whose consumer already refused a zone id, else the value trimmed.
     */
    public static @NonNull String canonicalZonelessNetwork(@NonNull String stored) {
        String canonical = stored.indexOf('%') < 0 ? canonicalNetwork(stored) : null;
        return canonical != null ? canonical : stored.trim();
    }
}
