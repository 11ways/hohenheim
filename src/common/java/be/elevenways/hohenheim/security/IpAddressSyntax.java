package be.elevenways.hohenheim.security;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;

/** Strict DNS-free IPv4, IPv6, and CIDR syntax used by shared configuration. */
public final class IpAddressSyntax {

    private IpAddressSyntax() {}

    public static byte @Nullable [] parse(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.indexOf(':') >= 0 ? parseV6(trimmed) : parseV4(trimmed);
    }

    public static boolean isNetwork(String value) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim();
        int slash = trimmed.indexOf('/');
        if (slash < 0) return parse(trimmed) != null;
        if (slash != trimmed.lastIndexOf('/')) return false;
        byte[] address = parse(trimmed.substring(0, slash));
        if (address == null) return false;
        try {
            int prefix = Integer.parseInt(trimmed.substring(slash + 1));
            return prefix >= 0 && prefix <= address.length * 8;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static byte @Nullable [] parseV4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            Integer octet = decimalOctet(parts[i]);
            if (octet == null) return null;
            bytes[i] = (byte) (int) octet;
        }
        return bytes;
    }

    private static @Nullable Integer decimalOctet(String part) {
        if (part.isEmpty() || part.length() > 3) return null;
        int value = 0;
        for (int i = 0; i < part.length(); i++) {
            char character = part.charAt(i);
            if (character < '0' || character > '9') return null;
            value = value * 10 + character - '0';
        }
        return value <= 255 ? value : null;
    }

    private static byte @Nullable [] parseV6(String value) {
        if (value.indexOf('%') >= 0) return null;
        int doubleColon = value.indexOf("::");
        if (doubleColon >= 0 && value.indexOf("::", doubleColon + 1) >= 0) return null;
        String head = doubleColon >= 0 ? value.substring(0, doubleColon) : value;
        String tail = doubleColon >= 0 ? value.substring(doubleColon + 2) : "";
        int[] headGroups = v6Groups(head, doubleColon < 0);
        int[] tailGroups = doubleColon >= 0 ? v6Groups(tail, true) : new int[0];
        if (headGroups == null || tailGroups == null) return null;
        int total = headGroups.length + tailGroups.length;
        if (doubleColon >= 0 ? total > 7 : total != 8) return null;
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

    private static int @Nullable [] v6Groups(String section, boolean allowEmbeddedV4) {
        if (section.isEmpty()) return new int[0];
        String[] parts = section.split(":", -1);
        int[] groups = new int[parts.length + 1];
        int count = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == parts.length - 1 && allowEmbeddedV4 && part.indexOf('.') >= 0) {
                byte[] v4 = parseV4(part);
                if (v4 == null) return null;
                groups[count++] = ((v4[0] & 0xff) << 8) | (v4[1] & 0xff);
                groups[count++] = ((v4[2] & 0xff) << 8) | (v4[3] & 0xff);
                continue;
            }
            Integer group = hexGroup(part);
            if (group == null) return null;
            groups[count++] = group;
        }
        return Arrays.copyOf(groups, count);
    }

    private static @Nullable Integer hexGroup(String part) {
        if (part.isEmpty() || part.length() > 4) return null;
        int value = 0;
        for (int i = 0; i < part.length(); i++) {
            int digit = Character.digit(part.charAt(i), 16);
            if (digit < 0) return null;
            value = (value << 4) | digit;
        }
        return value;
    }
}
