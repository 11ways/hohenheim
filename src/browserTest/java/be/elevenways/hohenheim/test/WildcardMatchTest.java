package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.zenit.server.http.HostPattern;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's wildcard tier on zenit's HostPattern: every pattern stored before M011 (a leading {@code *.} meaning
 * one or more labels) routes exactly the hosts it routed before once M011 respelled it, and the overlap rule asks the
 * same grammar. No DB, no proxy.
 */
class WildcardMatchTest {

    @Test
    void aRespelledLeadingWildcardStillMatchesSubdomainsAtAnyDepth() {
        assertThat(routes("a.example.com", "*.example.com")).isTrue();
        assertThat(routes("a.b.example.com", "*.example.com")).isTrue();
        assertThat(routes("a.b.c.example.com", "*.example.com")).isTrue();
        // What M011 respells is what is stored now; a new '*.' row names exactly one label.
        assertThat(HostPattern.parse("*.example.com").matches("a.b.example.com")).isFalse();
    }

    @Test
    void aRespelledLeadingWildcardStillNeverMatchesTheApexOrALookalike() {
        assertThat(routes("example.com", "*.example.com")).isFalse();
        assertThat(routes("aexample.com", "*.example.com")).isFalse();
        assertThat(routes("a.examplexcom", "*.example.com")).isFalse();
        assertThat(routes("a.example.com.evil.org", "*.example.com")).isFalse();
    }

    @Test
    void anInLabelStarStaysInsideItsLabel() {
        assertThat(routes("eu1.example.com", "eu*.example.com")).isTrue();
        assertThat(routes("eu.example.com", "eu*.example.com")).isTrue();
        assertThat(routes("eu-west-2.example.com", "eu*.example.com")).isTrue();
        assertThat(routes("eu1.x.example.com", "eu*.example.com")).isFalse();
        assertThat(routes("a.eu1.example.com", "eu*.example.com")).isFalse();
    }

    @Test
    void aQuestionMarkIsExactlyOneCharacter() {
        assertThat(routes("node-1.example.com", "node-?.example.com")).isTrue();
        assertThat(routes("node-x.example.com", "node-?.example.com")).isTrue();
        assertThat(routes("node-12.example.com", "node-?.example.com")).isFalse();
        assertThat(routes("node-.example.com", "node-?.example.com")).isFalse();
        assertThat(routes("node-..example.com", "node-?.example.com")).isFalse();
    }

    @Test
    void matchingIgnoresCase() {
        assertThat(routes("API.Example.COM", "*.example.com")).isTrue();
        assertThat(routes("api.example.com", "*.EXAMPLE.com")).isTrue();
    }

    @Test
    void aGloblessPatternBehavesAsAnExactMatch() {
        assertThat(routes("plain.example.com", "plain.example.com")).isTrue();
        // The dot is literal, never regex-any.
        assertThat(routes("plainxexample.com", "plain.example.com")).isFalse();
    }

    /**
     * Both tiers consult equally specific wildcards in the old matcher's order, over the shapes M011 stores: the tie
     * key is the regex source the old matcher compiled, never the pattern text.
     */
    @Test
    void equallySpecificWildcardsKeepTheOldMatchersOrder() {
        // 1. a?b before a-* ('[' sorts before the escaped '-'), although the text would put a-* first.
        assertThat(ordered("a-*.x.test", "a?b.x.test")).as("step 1: the old winner leads")
            .containsExactly("a?b.x.test", "a-*.x.test");
        // 2. A translated one-or-more run before a one-label glob of the same specificity, as before.
        assertThat(ordered(HostPattern.fromLegacyGlob("*.x.test"), "?.x.test")).as("step 2: the run leads")
            .containsExactly("**.x.test", "?.x.test");
        // 3. Every translated shape keeps the old matcher's key exactly.
        for (String legacy : List.of("*.x.test", "**.x.test", "a.**.x.test", "eu*.x.test", "node-?.x.test")) {
            HostPattern translated = HostPattern.parse(HostPattern.fromLegacyGlob(legacy));
            if (!legacy.contains("**")) {
                assertThat(HostnamePatterns.tieKey(translated)).as("step 3: %s", legacy)
                    .isEqualTo(HostnamePatterns.legacyTieKey(legacy, translated));
            }
        }
        // 4. Specificity still leads: a narrower pattern before a broader one whatever their keys.
        assertThat(ordered("**.com", "*.example.com")).as("step 4: narrower first")
            .containsExactly("*.example.com", "**.com");
    }

    private static List<String> ordered(String... patterns) {
        return java.util.Arrays.stream(patterns).map(HostPattern::parse)
            .sorted(HostnamePatterns.WILDCARD_ORDER).map(HostPattern::text).toList();
    }

    /** Whether a pattern stored before M011 routes the host once M011 translated it. */
    private static boolean routes(String host, String storedBeforeM011) {
        return HostPattern.parse(HostPattern.fromLegacyGlob(storedBeforeM011)).matches(host);
    }

    /**
     * The route-conflict question is set INTERSECTION, which string equality and one-way
     * matching both get wrong; the pathological pair {@code a*} / {@code *b} is the case a
     * witness-hostname shortcut would miss.
     */
    @Test
    void hostnameSetsIntersectWheneverSomeHostMatchesBoth() {
        // Exact under a wildcard, in both argument orders.
        assertThat(intersect("foo.example.com", "*.example.com")).isTrue();
        assertThat(intersect("*.example.com", "foo.example.com")).isTrue();
        assertThat(intersect("deep.sub.example.com", "*.example.com")).isTrue();

        // A leading "*." never covers the apex, so neither does the overlap rule.
        assertThat(intersect("example.com", "*.example.com")).isFalse();

        // Disjoint spaces stay disjoint.
        assertThat(intersect("foo.example.org", "*.example.com")).isFalse();
        assertThat(intersect("*.example.com", "*.example.org")).isFalse();
        assertThat(intersect("a.example.com", "b.example.com")).isFalse();

        // Wildcard against wildcard, including a nested space.
        assertThat(intersect("*.example.com", "*.sub.example.com")).isTrue();
        assertThat(intersect("eu*.example.com", "*.example.com")).isTrue();
        assertThat(intersect("a*.example.com", "*b.example.com")).isTrue();
        assertThat(intersect("a*.example.com", "b*.example.com")).isFalse();

        // Single-character globs.
        assertThat(intersect("node-?.example.com", "node-1.example.com")).isTrue();
        assertThat(intersect("node-?.example.com", "node-12.example.com")).isFalse();

        // Case folds, exactly like matching does.
        assertThat(intersect("FOO.Example.com", "*.example.COM")).isTrue();
    }

    /** Whether two patterns stored before M011 overlap once M011 translated them. */
    private static boolean intersect(String first, String second) {
        return HostnamePatterns.intersect(HostPattern.fromLegacyGlob(first), SiteDomainModel.MATCH_WILDCARD,
            HostPattern.fromLegacyGlob(second), SiteDomainModel.MATCH_WILDCARD);
    }

    /** The new grammar's one-label wildcard overlaps one depth only, and a run of any depth overlaps it. */
    @Test
    void aOneLabelWildcardOverlapsOneDepthOnly() {
        assertThat(HostnamePatterns.intersect("*.example.com", SiteDomainModel.MATCH_WILDCARD,
            "a.example.com", SiteDomainModel.MATCH_EXACT)).isTrue();
        assertThat(HostnamePatterns.intersect("*.example.com", SiteDomainModel.MATCH_WILDCARD,
            "a.b.example.com", SiteDomainModel.MATCH_EXACT)).isFalse();
        assertThat(HostnamePatterns.intersect("*.example.com", SiteDomainModel.MATCH_WILDCARD,
            "**.example.com", SiteDomainModel.MATCH_WILDCARD)).isTrue();
        assertThat(HostnamePatterns.intersect("*.example.com", SiteDomainModel.MATCH_WILDCARD,
            "*.sub.example.com", SiteDomainModel.MATCH_WILDCARD)).isFalse();
    }

    /**
     * A regex row is decided against a CONCRETE hostname by running the pattern; against a
     * glob or another pattern it still answers false, which the class documents as the
     * residue it deliberately does not fake.
     */
    @Test
    void regexRowsAreDecidedAgainstConcreteHostnamesOnly() {
        assertThat(HostnamePatterns.intersect("^app\\.example\\.com$", SiteDomainModel.MATCH_REGEX,
            "app.example.com", SiteDomainModel.MATCH_EXACT)).isTrue();
        assertThat(HostnamePatterns.intersect("app.example.com", SiteDomainModel.MATCH_EXACT,
            "^app\\.example\\.com$", SiteDomainModel.MATCH_REGEX)).isTrue();
        assertThat(HostnamePatterns.intersect("^app\\.example\\.com$", SiteDomainModel.MATCH_REGEX,
            "other.example.com", SiteDomainModel.MATCH_EXACT)).isFalse();
        assertThat(HostnamePatterns.intersect("^app\\.example\\.com$", SiteDomainModel.MATCH_REGEX,
            "^app\\.example\\.com$", SiteDomainModel.MATCH_REGEX)).isTrue();
        // The residue: regex versus glob is not decidable here and is not guessed.
        assertThat(HostnamePatterns.intersect("^app\\.example\\.com$", SiteDomainModel.MATCH_REGEX,
            "*.example.com", SiteDomainModel.MATCH_WILDCARD)).isFalse();
        // An uncompilable pattern routes nothing, so it matches nothing here either.
        assertThat(HostnamePatterns.intersect("[invalid", SiteDomainModel.MATCH_REGEX,
            "app.example.com", SiteDomainModel.MATCH_EXACT)).isFalse();
    }

    /** The tier a row routes in ignores match_type when the hostname carries globs. */
    @Test
    void effectiveKindFollowsTheHostnameNotOnlyTheColumn() {
        assertThat(HostnamePatterns.effectiveKind("*.example.com", SiteDomainModel.MATCH_EXACT))
            .isEqualTo(SiteDomainModel.MATCH_WILDCARD);
        assertThat(HostnamePatterns.effectiveKind("plain.example.com", SiteDomainModel.MATCH_WILDCARD))
            .isEqualTo(SiteDomainModel.MATCH_WILDCARD);
        assertThat(HostnamePatterns.effectiveKind("plain.example.com", SiteDomainModel.MATCH_EXACT))
            .isEqualTo(SiteDomainModel.MATCH_EXACT);
    }
}
