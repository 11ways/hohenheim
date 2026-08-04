package be.elevenways.hohenheim.net;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Textual IP-literal checks, TeaVM-safe (no java.net, no regex): the declared
 * server-address columns must hold a literal DNS can serve verbatim, never a hostname.
 */
public final class IpLiterals {

    private IpLiterals() {
    }

    /** Strict dotted-quad IPv4: four decimal octets 0-255, no leading-zero octets. */
    public static boolean isIpv4(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int octets = 0;
        int index = 0;
        int length = value.length();
        while (index < length) {
            int start = index;
            int octet = 0;
            while (index < length && value.charAt(index) >= '0' && value.charAt(index) <= '9') {
                octet = octet * 10 + (value.charAt(index) - '0');
                index++;
                if (index - start > 3) {
                    return false;
                }
            }
            int digits = index - start;
            if (digits == 0 || octet > 255 || (digits > 1 && value.charAt(start) == '0')) {
                return false;
            }
            octets++;
            if (index < length) {
                if (value.charAt(index) != '.' || octets == 4) {
                    return false;
                }
                index++;
                if (index == length) {
                    return false;   // trailing dot
                }
            }
        }
        return octets == 4;
    }

    /**
     * Structural IPv6: hex groups separated by {@code :}, at most one {@code ::}, group
     * count consistent with the compression. No zone index, no embedded IPv4 form --
     * the columns hold canonical literals a zone file can carry.
     */
    public static boolean isIpv6(@Nullable String value) {
        if (value == null || value.length() < 2) {
            return false;
        }
        int doubleColon = value.indexOf("::");
        if (doubleColon >= 0 && value.indexOf("::", doubleColon + 1) >= 0) {
            return false;   // two compressions
        }
        // A leading/trailing single colon is only legal as part of "::".
        if (value.startsWith(":") && !value.startsWith("::")) {
            return false;
        }
        if (value.endsWith(":") && !value.endsWith("::")) {
            return false;
        }
        int groups = 0;
        int groupLength = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') {
                if (groupLength > 0) {
                    groups++;
                    groupLength = 0;
                }
                continue;
            }
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
            groupLength++;
            if (groupLength > 4) {
                return false;
            }
        }
        if (groupLength > 0) {
            groups++;
        }
        return doubleColon >= 0 ? groups < 8 : groups == 8;
    }
}
