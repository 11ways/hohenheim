package be.elevenways.hohenheim.net;

import be.elevenways.zenit.common.net.IpRanges;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Textual IP-literal checks for the declared server-address columns, which must hold a literal
 * DNS can serve verbatim, never a hostname; TeaVM-safe because the parsing is zenit's common
 * {@link IpRanges}.
 *
 * AIDEV-NOTE: stricter than {@link IpRanges#parseLiteral} on purpose, and only in SPELLING: a
 * zone file carries the canonical literal, so an IPv4 octet with a leading zero, an IPv6 zone
 * index and an embedded dotted quad are refused here even though the parser reads them.
 */
public final class IpLiterals {

    private IpLiterals() {
    }

    /** Strict dotted-quad IPv4: four decimal octets 0-255, no leading-zero octets. */
    public static boolean isIpv4(@Nullable String value) {
        if (value == null || value.isEmpty() || value.indexOf(':') >= 0) {
            return false;
        }
        byte[] bytes = IpRanges.parseLiteral(value);
        // The canonical round trip is what refuses a leading-zero octet ("010.0.0.1").
        return bytes != null && bytes.length == 4 && value.equals((bytes[0] & 0xFF) + "."
            + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF));
    }

    /**
     * Structural IPv6: hex groups separated by {@code :}, at most one {@code ::}, group
     * count consistent with the compression. No zone index, no embedded IPv4 form -- the
     * columns hold canonical literals a zone file can carry.
     */
    public static boolean isIpv6(@Nullable String value) {
        if (value == null || value.indexOf(':') < 0 || value.indexOf('%') >= 0
                || value.indexOf('.') >= 0) {
            return false;
        }
        // Not a length check: an all-hex IPv4-mapped spelling folds to 4 bytes and is still a
        // well-formed IPv6 literal.
        return IpRanges.parseLiteral(value) != null;
    }

    /**
     * Whether a configured value is one literal address or one CIDR range (trimmed, no DNS,
     * no zone index), the shape the PROXY-protocol trusted-source list stores.
     *
     * AIDEV-NOTE: the parsing is zenit's {@link IpRanges#parseLiteral}; the prefix bound is
     * read off the SPELLING's family (128 for anything with a colon), exactly as the parser
     * this replaced did, because a stored value it accepted must keep coercing after an
     * upgrade. An IPv4-mapped entry with a prefix over 32 is therefore still ACCEPTED here
     * although the listener's matcher, which bounds the prefix by the folded 4 bytes,
     * ignores it as malformed.
     */
    public static boolean isNetwork(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        int slash = trimmed.indexOf('/');
        String address = (slash < 0 ? trimmed : trimmed.substring(0, slash)).trim();
        if (address.indexOf('%') >= 0 || IpRanges.parseLiteral(address) == null) {
            return false;
        }
        if (slash < 0) {
            return true;
        }
        if (slash != trimmed.lastIndexOf('/')) {
            return false;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(trimmed.substring(slash + 1));
        } catch (NumberFormatException malformed) {
            return false;
        }
        return prefix >= 0 && prefix <= (address.indexOf(':') >= 0 ? 128 : 32);
    }
}
