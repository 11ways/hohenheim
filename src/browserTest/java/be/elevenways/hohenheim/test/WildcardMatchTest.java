package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.server.proxy.HostnamePatterns;
import be.elevenways.hohenheim.server.proxy.WildcardHostname;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure glob-to-regex tests for {@link WildcardHostname}. No DB, no proxy.
 */
class WildcardMatchTest {

    @Test
    void leadingStarDotMatchesSubdomainsAtAnyDepth() {
        assertThat(WildcardHostname.matches("a.example.com", "*.example.com")).isTrue();
        assertThat(WildcardHostname.matches("a.b.example.com", "*.example.com")).isTrue();
        assertThat(WildcardHostname.matches("a.b.c.example.com", "*.example.com")).isTrue();
    }

    @Test
    void leadingStarDotExcludesApexAndLookalikes() {
        // Matches the Node original and the previous endsWith() matcher: no apex.
        assertThat(WildcardHostname.matches("example.com", "*.example.com")).isFalse();
        assertThat(WildcardHostname.matches("aexample.com", "*.example.com")).isFalse();
        assertThat(WildcardHostname.matches("a.examplexcom", "*.example.com")).isFalse();
        assertThat(WildcardHostname.matches("a.example.com.evil.org", "*.example.com")).isFalse();
    }

    @Test
    void midStringStarStaysWithinOneLabel() {
        assertThat(WildcardHostname.matches("eu1.example.com", "eu*.example.com")).isTrue();
        assertThat(WildcardHostname.matches("eu.example.com", "eu*.example.com")).isTrue();
        assertThat(WildcardHostname.matches("eu-west-2.example.com", "eu*.example.com")).isTrue();

        assertThat(WildcardHostname.matches("eu1.x.example.com", "eu*.example.com")).isFalse();
        assertThat(WildcardHostname.matches("a.eu1.example.com", "eu*.example.com")).isFalse();
    }

    @Test
    void questionMarkMatchesExactlyOneNonDotCharacter() {
        assertThat(WildcardHostname.matches("node-1.example.com", "node-?.example.com")).isTrue();
        assertThat(WildcardHostname.matches("node-x.example.com", "node-?.example.com")).isTrue();

        assertThat(WildcardHostname.matches("node-12.example.com", "node-?.example.com")).isFalse();
        assertThat(WildcardHostname.matches("node-.example.com", "node-?.example.com")).isFalse();
        assertThat(WildcardHostname.matches("node-..example.com", "node-?.example.com")).isFalse();
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertThat(WildcardHostname.matches("API.Example.COM", "*.example.com")).isTrue();
        assertThat(WildcardHostname.matches("api.example.com", "*.EXAMPLE.com")).isTrue();
    }

    @Test
    void globlessPatternBehavesAsExactMatch() {
        assertThat(WildcardHostname.matches("plain.example.com", "plain.example.com")).isTrue();
        // The dot must be literal, not regex-any.
        assertThat(WildcardHostname.matches("plainxexample.com", "plain.example.com")).isFalse();
    }

    @Test
    void isWildcardDetectsGlobCharacters() {
        assertThat(WildcardHostname.isWildcard("*.example.com")).isTrue();
        assertThat(WildcardHostname.isWildcard("eu?.example.com")).isTrue();
        assertThat(WildcardHostname.isWildcard("plain.example.com")).isFalse();
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

    private static boolean intersect(String first, String second) {
        return HostnamePatterns.intersect(first, SiteDomainModel.MATCH_WILDCARD,
            second, SiteDomainModel.MATCH_WILDCARD);
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
