package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.net.Hostnames;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import java.util.ArrayList;
import java.util.List;
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
        String origin = canonicalName(input);
        if (origin.isEmpty() || origin.contains("*")) {
            return null;
        }
        for (String label : origin.split("\\.", -1)) {
            if (!isLabel(label, false)) {
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
        String owner = canonicalName(input);
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
            if (!isLabel(label, true)) {
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

    /** @return the name folded and stripped of trailing dots, comparable against an origin */
    public static @NonNull String canonicalName(@NonNull String fqdn) {
        return Hostnames.stripTrailingDots(fqdn.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Every suffix of a name that could be the origin of a zone containing it, itself
     * included, longest first -- one candidate per label, so an indexed origin lookup
     * replaces a walk over every hosted zone.
     */
    public static @NonNull List<String> candidateOrigins(@NonNull String fqdn) {
        List<String> origins = new ArrayList<>();
        String rest = canonicalName(fqdn);
        while (!rest.isEmpty()) {
            origins.add(rest);
            int dot = rest.indexOf('.');
            if (dot < 0) {
                break;
            }
            rest = rest.substring(dot + 1);
        }
        return origins;
    }

    /** @return the relative owner for a fully qualified name, or null when outside the zone */
    public static @Nullable String relative(@NonNull String origin, @NonNull String fqdn) {
        String name = canonicalName(fqdn);
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

    /**
     * The host an owner name's attribute leaves hang off (RFC 8552): the name with its leading underscore labels
     * removed, so {@code _25._tcp.mx.example.com} and {@code _dmarc.example.com} answer to {@code mx.example.com} and
     * {@code example.com}.
     *
     * AIDEV-NOTE: THE one rule for whose an underscore name is. Hostnames never carry an underscore label (a site
     * domain refuses one), so without it no exact domain row could ever cover an SRV, TLSA, _dmarc or
     * _acme-challenge name, and the nearest wildcard owner decided it instead of the host's own site. Only LEADING
     * underscore labels are stripped: a DKIM selector name ({@code sel._domainkey.host}) starts with a plain label
     * and answers to itself.
     *
     * @return the canonical host, or "" when every label is an underscore label
     */
    public static @NonNull String attributeHost(@NonNull String fqdn) {
        String rest = canonicalName(fqdn);
        while (rest.startsWith("_")) {
            int dot = rest.indexOf('.');
            if (dot < 0) {
                return "";
            }
            rest = rest.substring(dot + 1);
        }
        return rest;
    }

    /**
     * Whether a name has the {@code _<port>._<tcp|udp|sctp>.<host>} shape of a TLSA owner (RFC 6698 section 3), its
     * host being the {@link #attributeHost} of the name.
     */
    public static boolean isServicePortName(@NonNull String fqdn) {
        String[] labels = canonicalName(fqdn).split("\\.", -1);
        if (labels.length < 3 || !labels[0].matches("_[0-9]{1,5}") || labels[2].startsWith("_")) {
            return false;
        }
        int port = Integer.parseInt(labels[0].substring(1));
        return port <= 65535 && List.of("_tcp", "_udp", "_sctp").contains(labels[1]);
    }

    /**
     * A hostname label (letters, digits, hyphens; no leading or trailing hyphen), or with {@code underscores} an owner
     * label, which also allows underscores (_acme-challenge, _dmarc, ...).
     */
    private static boolean isLabel(@NonNull String label, boolean underscores) {
        if (label.isEmpty() || label.length() > 63) {
            return false;
        }
        if (label.startsWith("-") || label.endsWith("-")) {
            return false;
        }
        return label.matches(underscores ? "[a-z0-9_-]+" : "[a-z0-9-]+");
    }
}
