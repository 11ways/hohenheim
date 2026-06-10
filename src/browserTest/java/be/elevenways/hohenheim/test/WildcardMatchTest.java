package be.elevenways.hohenheim.test;

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
}
