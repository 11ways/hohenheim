package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.protoblast.common.util.BlastString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether two configured hostnames can ever match the SAME request host, and which routing
 * tier a row actually lands in.
 *
 * AIDEV-NOTE: route conflict is a question about hostname SETS INTERSECTING, never about
 * literal string equality. String equality misses the takeover that matters: tenant A holds
 * {@code *.example.com} (wildcard tier) while tenant B claims {@code foo.example.com}
 * (exact tier), the two spell DIFFERENT claim keys so no unique index refuses them, and
 * SiteDispatcher consults the exact tier FIRST -- so B silently seizes a host A was serving.
 * Do not "simplify" the callers back to Objects.equals.
 *
 * @author Jelle De Loecker
 */
public final class HostnamePatterns {

    private HostnamePatterns() {
    }

    /**
     * The tier a row actually routes in, which is NOT simply its match_type: a hostname
     * carrying glob characters routes as a wildcard whatever the column says.
     *
     * AIDEV-NOTE: the decision itself moved to {@link SiteDomainModel#effectiveMatchType}
     * (common) so the model's own write-time syntax check and the tenant refusal ask the
     * SAME question the dispatcher answers -- a second copy on the server side is exactly
     * how the column and the routed tier drifted apart. This stays as the routing-side
     * name every proxy caller already spells.
     *
     * @return one of the {@code SiteDomainModel.MATCH_*} constants
     */
    public static @NonNull String effectiveKind(@Nullable String hostname, @Nullable String matchType) {
        return SiteDomainModel.effectiveMatchType(hostname, matchType);
    }

    /**
     * Whether two rows' hostnames can both match one request host.
     *
     * AIDEV-NOTE: a REGEX row is decided against a CONCRETE hostname by running the pattern
     * over it -- that direction is exactly decidable, and it is the one that matters: a
     * regex row spells a claim key nothing can equal and carries no glob to walk, so leaving
     * it at literal equality let a regex row seize a released hostname outright (the
     * "regex is consulted last" bound is empty for a hostname NOTHING claims -- which is
     * what "released" means). See ReleasedClaimRegexShadowTest, which drives it.
     *
     * RESIDUE, deliberately open: regex versus GLOB (and two different regexes) still
     * answers false. Whether an arbitrary regex intersects a glob is not decidable here and
     * approximating it would be a guess wearing an authority's clothes. What that leaves
     * open is a regex row shadowing a WILDCARD row -- so a wildcard released by one tenant
     * can still be re-entered by another tenant's regex. That residue is bounded by regex
     * rows being ADMIN-ONLY (TenantWrites.checkDomainWrite refuses any non-exact match type
     * from a tenant-originated write): both sides of the remaining case have to be authored
     * by an operator. If regex ever becomes tenant-reachable, this must be closed first.
     */
    public static boolean intersect(@Nullable String hostnameA, @Nullable String matchTypeA,
                                    @Nullable String hostnameB, @Nullable String matchTypeB) {
        String canonicalA = SiteDomainModel.canonicalHostname(hostnameA, matchTypeA);
        String canonicalB = SiteDomainModel.canonicalHostname(hostnameB, matchTypeB);
        if (canonicalA == null || canonicalB == null) {
            return false;
        }
        if (canonicalA.equals(canonicalB)) {
            return true;
        }
        boolean regexA = SiteDomainModel.MATCH_REGEX.equals(effectiveKind(canonicalA, matchTypeA));
        boolean regexB = SiteDomainModel.MATCH_REGEX.equals(effectiveKind(canonicalB, matchTypeB));
        if (regexA || regexB) {
            // Two regexes: matching is case-insensitive (HostnameRegex), so sources equal
            // MODULO CASE are one pattern in effect and must intersect -- otherwise
            // "^App\.x$" and "^app\.x$" were two admitted routes serving the same hosts.
            // (\S versus \s also collapses here; that is a FALSE conflict, fails closed,
            // and a whitespace class matches no real hostname.) Beyond that, pattern
            // equivalence stays the undecidable case.
            if (regexA && regexB) {
                return canonicalA.equalsIgnoreCase(canonicalB);
            }
            return regexMatchesHost(regexA ? canonicalA : canonicalB, regexA ? canonicalB : canonicalA);
        }
        return globsIntersect(canonicalA, canonicalB);
    }

    /**
     * Whether a regex row would route one CONCRETE hostname.
     *
     * AIDEV-NOTE: this deliberately ignores the dispatcher's anti-probe guards
     * (SiteDispatcher.isSuspiciousRegexHostname, the dot-count ceiling, the dotted
     * "project" capture). Those only ever REMOVE matches, so ignoring them
     * over-approximates the pattern's reach and therefore fails CLOSED -- the wrong
     * direction to be wrong in for a takeover refusal. A pattern that does not compile
     * routes nothing at all (SiteDispatcher drops it), so it matches nothing here either.
     */
    private static boolean regexMatchesHost(@NonNull String pattern, @NonNull String hostname) {
        if (hostname.isEmpty() || WildcardHostname.isWildcard(hostname)) {
            return false;
        }
        try {
            Pattern compiled = HostnameRegex.compile(pattern);
            return compiled != null && compiled.matcher(hostname).matches();
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Whether a configured row's hostname set CONTAINS every host the requested certificate
     * name can present.
     *
     * AIDEV-NOTE: coverage is one-directional and strictly stronger than {@link #intersect}.
     * An exact row {@code a.example.com} INTERSECTS the request {@code *.example.com}
     * without covering it, and issuing on that intersection hands whoever serves one host a
     * certificate valid for every sibling under the parent -- a cross-tenant certificate.
     * A wildcard request is therefore only covered by a row that is ITSELF a leading-"*."
     * wildcard whose base is the request's base or a suffix of it. Anything this layer
     * cannot decide (a regex row, an in-label glob such as {@code a*.example.com}) fails
     * CLOSED, because "probably a superset" is not an authority.
     *
     * @param requestedName an ACME SAN: an exact hostname or one leading {@code *.} label
     */
    public static boolean covers(@Nullable String rowHostname, @Nullable String rowMatchType,
                                 @Nullable String requestedName) {
        String pattern = SiteDomainModel.canonicalHostname(rowHostname, rowMatchType);
        if (pattern == null || pattern.isEmpty() || requestedName == null) {
            return false;
        }
        // The REQUESTED side is folded exactly like a stored hostname (canonicalHostname):
        // an ACME SAN or a DNS name arriving with the root dot is the same name without it.
        String requested = Hostnames.stripTrailingDots(BlastString.lower(requestedName.trim()));
        if (requested.isEmpty()) {
            return false;
        }
        if (SiteDomainModel.MATCH_REGEX.equals(effectiveKind(pattern, rowMatchType))) {
            return false;
        }
        if (!requested.startsWith("*.")) {
            return WildcardHostname.matches(requested, pattern);
        }
        if (!pattern.startsWith("*.")) {
            return false;
        }
        String patternBase = pattern.substring(2);
        if (patternBase.isEmpty() || WildcardHostname.isWildcard(patternBase)) {
            return false;
        }
        String requestedBase = requested.substring(2);
        return requestedBase.equals(patternBase) || requestedBase.endsWith("." + patternBase);
    }

    /**
     * Whether two glob hostnames share at least one host. A plain hostname is a glob with
     * no metacharacters, so exact/exact and exact/wildcard ride the same code path.
     */
    static boolean globsIntersect(@NonNull String first, @NonNull String second) {
        List<String> labelsFirst = labelsOf(first);
        List<String> labelsSecond = labelsOf(second);
        Boolean[][] memo = new Boolean[labelsFirst.size() + 1][labelsSecond.size() + 1];
        return labelsIntersect(labelsFirst, 0, labelsSecond, 0, memo);
    }

    /**
     * The label sequence of a glob. {@link WildcardHostname} gives a LEADING {@code *.} the
     * only label-count freedom in the language (one or more whole labels); every other glob
     * character stays inside its own label.
     */
    private static @NonNull List<String> labelsOf(@NonNull String glob) {
        List<String> labels = new ArrayList<>();
        String rest = glob;
        if (rest.startsWith("*.")) {
            labels.add(ANY_LABELS);
            rest = rest.substring(2);
        }
        int start = 0;
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == '.') {
                labels.add(rest.substring(start, i));
                start = i + 1;
            }
        }
        labels.add(rest.substring(start));
        return labels;
    }

    /** Sentinel label meaning "one or more arbitrary labels"; '/' cannot occur in a glob. */
    private static final String ANY_LABELS = "/+";

    private static boolean labelsIntersect(@NonNull List<String> first, int i,
                                           @NonNull List<String> second, int j,
                                           Boolean[][] memo) {
        if (i == first.size() && j == second.size()) {
            return true;
        }
        if (i == first.size() || j == second.size()) {
            // One side still demands at least one label the other can no longer supply.
            return false;
        }
        Boolean known = memo[i][j];
        if (known != null) {
            return known;
        }
        String a = first.get(i);
        String b = second.get(j);
        boolean anyA = ANY_LABELS.equals(a);
        boolean anyB = ANY_LABELS.equals(b);
        boolean result;
        if (anyA && anyB) {
            // Both consume one or more arbitrary labels: they can consume the same count,
            // or either can keep consuming while the other moves on.
            result = labelsIntersect(first, i + 1, second, j + 1, memo)
                || labelsIntersect(first, i, second, j + 1, memo)
                || labelsIntersect(first, i + 1, second, j, memo);
        } else if (anyA) {
            // Any single-label glob matches at least one NON-EMPTY label, so the only
            // question left is how many labels the run swallows.
            result = labelsIntersect(first, i + 1, second, j + 1, memo)
                || labelsIntersect(first, i, second, j + 1, memo);
        } else if (anyB) {
            result = labelsIntersect(first, i + 1, second, j + 1, memo)
                || labelsIntersect(first, i + 1, second, j, memo);
        } else {
            result = labelGlobsIntersect(a, b)
                && labelsIntersect(first, i + 1, second, j + 1, memo);
        }
        memo[i][j] = result;
        return result;
    }

    /**
     * Whether two single-label globs ({@code *} = zero or more non-dot characters,
     * {@code ?} = exactly one) share a string.
     */
    static boolean labelGlobsIntersect(@NonNull String first, @NonNull String second) {
        Boolean[][] memo = new Boolean[first.length() + 1][second.length() + 1];
        return labelGlobsIntersect(first, 0, second, 0, memo);
    }

    private static boolean labelGlobsIntersect(@NonNull String first, int x,
                                               @NonNull String second, int y,
                                               Boolean[][] memo) {
        if (x == first.length() && y == second.length()) {
            return true;
        }
        Boolean known = memo[x][y];
        if (known != null) {
            return known;
        }
        boolean result;
        if (x == first.length()) {
            result = onlyStars(second, y);
        } else if (y == second.length()) {
            result = onlyStars(first, x);
        } else {
            char a = first.charAt(x);
            char b = second.charAt(y);
            if (a == '*') {
                result = labelGlobsIntersect(first, x + 1, second, y, memo)
                    || labelGlobsIntersect(first, x, second, y + 1, memo);
            } else if (b == '*') {
                result = labelGlobsIntersect(first, x, second, y + 1, memo)
                    || labelGlobsIntersect(first, x + 1, second, y, memo);
            } else {
                result = (a == '?' || b == '?' || a == b)
                    && labelGlobsIntersect(first, x + 1, second, y + 1, memo);
            }
        }
        memo[x][y] = result;
        return result;
    }

    /** @return true when the remainder can match the empty string */
    private static boolean onlyStars(@NonNull String glob, int from) {
        for (int i = from; i < glob.length(); i++) {
            if (glob.charAt(i) != '*') {
                return false;
            }
        }
        return true;
    }
}
