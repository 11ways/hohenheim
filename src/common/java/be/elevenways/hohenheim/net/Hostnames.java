package be.elevenways.hohenheim.net;

import be.elevenways.protoblast.common.util.BlastString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE hostname syntax authority: what a name may be SPELLED like, for every tier that
 * stores one.
 *
 * AIDEV-NOTE: this is the promoted body of what used to be {@code AcmeService.isValidHostname},
 * which was wired to the certificate-request handler ALONE while the site-domain write path
 * had no syntax check at all. Two spellings of "is this a valid hostname" is what let a
 * glob-shaped hostname be stored under {@code match_type=exact} (routing as a wildcard, since
 * the dispatcher reads the SHAPE) and let {@code victim.test.} be stored as a second,
 * non-intersecting claim key for a name TLS can only ever present without the dot. There is
 * ONE definition here and every caller reaches it; do not add a second.
 *
 * TeaVM-safe on purpose (no regex, no java.util.Locale, no String.format): the check has to
 * be reachable from {@code SiteDomainModel}'s beforeValidate hook, which lives in the common
 * source set, because a hook on the model is the only seam EVERY writer passes -- CMS form,
 * revision restore, zone import, peer API, seeds and a bare {@code model.save} alike.
 *
 * @author Jelle De Loecker
 */
public final class Hostnames {

    /** RFC 1035 name ceiling in presentation form. */
    public static final int MAX_LENGTH = 253;

    /** RFC 1035 label ceiling. */
    public static final int MAX_LABEL_LENGTH = 63;

    private Hostnames() {
    }

    /** @return whether the value carries a glob metacharacter, so it routes in the wildcard tier */
    public static boolean hasGlobCharacters(@Nullable String value) {
        return value != null && (value.indexOf('*') >= 0 || value.indexOf('?') >= 0);
    }

    /**
     * Fold the FQDN's trailing root dot away, so {@code victim.test.} and {@code victim.test}
     * are one name.
     *
     * AIDEV-NOTE: they are the same name in DNS, but they were two different claim keys here
     * while the HTTP route table kept the dot and SNI structurally cannot carry one
     * (RFC 6066; java.net.SNIHostName rejects it outright). That gap handed the dotted
     * spelling the victim's certificate and the raider's upstream. Folding at the ONE
     * canonicalization point closes it for the conflict scan, the quarantine ledger and the
     * route table together; {@code SiteDispatcher.extractHostname} folds the request side to
     * the same form so no traffic can reach the gap either.
     */
    public static @NonNull String stripTrailingDots(@NonNull String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    /**
     * Whether the value is a syntactically valid RFC-1123 name, WITHOUT requiring a dot --
     * a single-label proxy route ({@code localhost}, an internal short name) is legitimate.
     */
    public static boolean isValidLabelSequence(@Nullable String value) {
        return isValidLabelSequence(value, false);
    }

    /**
     * The same syntax with glob metacharacters allowed inside labels, which is what an
     * operator-authored wildcard row legitimately stores.
     */
    public static boolean isValidGlob(@Nullable String value) {
        return isValidLabelSequence(value, true);
    }

    /**
     * The ACME face: a valid name that is also PUBLIC enough to be certified, which needs at
     * least one dot and no glob (HTTP-01 cannot validate a wildcard).
     */
    public static boolean isValidHostname(@Nullable String value) {
        if (!isValidLabelSequence(value, false)) {
            return false;
        }
        return normalize(value).indexOf('.') >= 0;
    }

    /**
     * Whether a value can occupy one field of a route tuple: no whitespace and no control
     * character.
     *
     * AIDEV-NOTE: this is what makes {@code RouteClaims}' claim key honest. That key joins
     * hostname, path and listeners with a NEWLINE and its docblock asserts "a newline can
     * occur in no part of a route tuple" -- which was true of paths and listener lists and
     * merely HOPED of hostnames, since nothing validated one. A regex row is the only tier
     * whose source is not a name at all, so it is held to this and nothing more.
     */
    public static boolean isSingleLine(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= ' ' || character == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidLabelSequence(@Nullable String value, boolean allowGlob) {
        if (value == null) {
            return false;
        }
        String name = normalize(value);
        if (name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        int labelStart = 0;
        for (int i = 0; i <= name.length(); i++) {
            if (i == name.length() || name.charAt(i) == '.') {
                if (!isValidLabel(name, labelStart, i, allowGlob)) {
                    return false;
                }
                labelStart = i + 1;
            }
        }
        return true;
    }

    /** One label of {@code name}, between {@code from} inclusive and {@code to} exclusive. */
    private static boolean isValidLabel(@NonNull String name, int from, int to, boolean allowGlob) {
        int length = to - from;
        if (length == 0 || length > MAX_LABEL_LENGTH) {
            return false;
        }
        for (int i = from; i < to; i++) {
            char character = name.charAt(i);
            boolean alphanumeric = (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9');
            boolean glob = allowGlob && (character == '*' || character == '?');
            if (character == '-') {
                // A leading or trailing hyphen is the classic IDN/punycode spoofing hook.
                if (i == from || i == to - 1) {
                    return false;
                }
            } else if (!alphanumeric && !glob) {
                return false;
            }
        }
        return true;
    }

    /** Trim, case-fold and drop the root dot, so every predicate judges the stored form. */
    private static @NonNull String normalize(@Nullable String value) {
        return value == null ? "" : stripTrailingDots(BlastString.lower(value.trim()));
    }
}
