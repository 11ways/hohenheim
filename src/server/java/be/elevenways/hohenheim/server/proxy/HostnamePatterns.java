package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.net.Hostnames;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.server.http.HostPattern;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 * AIDEV-NOTE: a non-regex hostname IS a zenit HostPattern ({@code *.} exactly one label,
 * {@code **.} one or more): overlap, coverage and the dispatcher's wildcard matching all ask
 * it, so the write-time checks and live routing read one grammar. A stored spelling it
 * refuses names no host: it routes nothing (RouteTableBuilder drops it) and therefore
 * intersects and covers nothing here.
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
        HostPattern patternA = HostPattern.tryParse(canonicalA);
        HostPattern patternB = HostPattern.tryParse(canonicalB);
        return patternA != null && patternB != null
            && patternA.overlap(patternB) == HostPattern.Overlap.OVERLAPS;
    }

    /**
     * THE glob-specificity measure, shared by the HTTP wildcard tier and the TLS/SNI table so
     * the two tiers order candidates identically: the characters a matching host must spell
     * literally, the dots between spelled labels included.
     */
    static int specificity(@NonNull HostPattern pattern) {
        return pattern.literalCharacters() + pattern.labelsSpelled() - 1;
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
        if (hostname.isEmpty() || Hostnames.hasGlobCharacters(hostname)) {
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
     * A wildcard request is therefore only covered by a row whose own wildcard spans at least
     * every host the request names. Anything HostPattern cannot decide (a regex row, an
     * in-label glob over another glob) fails CLOSED, because "probably a superset" is not an
     * authority.
     *
     * @param requestedName an ACME SAN: an exact hostname or a wildcard of exactly one leading label
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
        // An identical spelling covers itself, as in intersect, even one stored before the
        // grammar refused it: the row claims exactly that name.
        if (pattern.equals(requested)) {
            return true;
        }
        HostPattern row = HostPattern.tryParse(pattern);
        HostPattern name = HostPattern.tryParse(requested);
        return row != null && name != null && row.covers(name);
    }
}
