package be.elevenways.hohenheim.server.dns;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.Locale;

/**
 * Owner-name normalization for hosted zones.
 * Origins are canonical lowercase without trailing dot; record owners are
 * relative ("@" = apex, leftmost "*" = wildcard, "_" labels allowed).
 */
// AIDEV-NOTE: every fold here is Locale.ROOT because RFC 4343 mandates ASCII-ONLY case
// folding for DNS names. A no-arg toLowerCase() under -Duser.language=tr maps 'I' to
// dotless 'ı', which fails the [a-z0-9-] label regex outright (a valid origin is rejected)
// and silently mangles relative() (API.wiki.example.com resolves to owner "apı").
public final class DnsNames {

    public static final String APEX = "@";

    private DnsNames() {}

    /** @return the canonical origin, or null when the input is not a valid zone origin */
    public static @Nullable String normalizeOrigin(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String origin = input.trim().toLowerCase(Locale.ROOT);
        while (origin.endsWith(".")) {
            origin = origin.substring(0, origin.length() - 1);
        }
        if (origin.isEmpty() || origin.contains("*")) {
            return null;
        }
        for (String label : origin.split("\\.", -1)) {
            if (!isHostLabel(label)) {
                return null;
            }
        }
        return origin;
    }

    /** @return the canonical relative owner, or null when the input is not a valid owner name */
    public static @Nullable String normalizeOwner(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String owner = input.trim().toLowerCase(Locale.ROOT);
        while (owner.endsWith(".")) {
            owner = owner.substring(0, owner.length() - 1);
        }
        if (owner.isEmpty() || APEX.equals(owner)) {
            return APEX;
        }
        String[] labels = owner.split("\\.", -1);
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            if ("*".equals(label)) {
                if (i != 0) {
                    return null;
                }
                continue;
            }
            if (!isOwnerLabel(label)) {
                return null;
            }
        }
        return owner;
    }

    /** @return the fully qualified name (no trailing dot) for a relative owner in a zone */
    public static @NonNull String absolute(@NonNull String origin, @NonNull String owner) {
        if (APEX.equals(owner)) {
            return origin;
        }
        return owner + "." + origin;
    }

    /** @return the relative owner for a fully qualified name, or null when outside the zone */
    public static @Nullable String relative(@NonNull String origin, @NonNull String fqdn) {
        String name = fqdn.toLowerCase(Locale.ROOT);
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.equals(origin)) {
            return APEX;
        }
        if (name.endsWith("." + origin)) {
            return name.substring(0, name.length() - origin.length() - 1);
        }
        return null;
    }

    /** @return true when the fqdn is at or below the zone origin */
    public static boolean zoneContains(@NonNull String origin, @NonNull String fqdn) {
        return relative(origin, fqdn) != null;
    }

    /** Hostname label: letters, digits, hyphens; no leading/trailing hyphen. */
    private static boolean isHostLabel(@NonNull String label) {
        if (label.isEmpty() || label.length() > 63) {
            return false;
        }
        if (label.startsWith("-") || label.endsWith("-")) {
            return false;
        }
        return label.matches("[a-z0-9-]+");
    }

    /** Owner label: hostname label, but underscores allowed (_acme-challenge, _dmarc, ...). */
    private static boolean isOwnerLabel(@NonNull String label) {
        if (label.isEmpty() || label.length() > 63) {
            return false;
        }
        if (label.startsWith("-") || label.endsWith("-")) {
            return false;
        }
        return label.matches("[a-z0-9_-]+");
    }
}
