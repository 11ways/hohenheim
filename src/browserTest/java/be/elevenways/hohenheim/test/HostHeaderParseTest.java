package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.net.Hostnames;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Host header is an RFC 3986 AUTHORITY, and folding one to a route key is one
 * definition, not one per dispatcher.
 *
 * AIDEV-NOTE: {@code host.indexOf(':')} truncated {@code [::1]:80} to {@code "["} -- an
 * IPv6 literal's port separator is the colon AFTER the closing bracket. Two dispatchers
 * carried their own copy of that parse. This pins the parse AND the honest consequence:
 * a correct parse does NOT make an IPv6 literal a reachable site, because the exact tier's
 * stored spelling can never contain a colon.
 */
class HostHeaderParseTest {

    @Test
    void hostHeaderFoldsToOneRouteKeyIncludingIpv6Literals() {
        // 1. The ordinary cases keep working: port stripped, case folded, root dot folded.
        assertThat(Hostnames.fromHostHeader("WIKI.example.test:8443"))
            .as("step 1: a named host loses its port and folds case")
            .isEqualTo("wiki.example.test");
        assertThat(Hostnames.fromHostHeader("wiki.example.test"))
            .as("step 1: a portless host is unchanged")
            .isEqualTo("wiki.example.test");
        assertThat(Hostnames.fromHostHeader("wiki.example.test."))
            .as("step 1: the root dot still folds, as SNI can never carry one")
            .isEqualTo("wiki.example.test");
        assertThat(Hostnames.fromHostHeader("203.0.113.7:80"))
            .as("step 1: an IPv4 literal keeps its address, loses its port")
            .isEqualTo("203.0.113.7");

        // 2. THE BUG: a bracketed IPv6 literal. The port separator is the colon AFTER the
        //    closing bracket, so the old parse handed the route table a bare "[".
        assertThat(Hostnames.fromHostHeader("[::1]:80"))
            .as("step 2: a bracketed IPv6 literal with a port yields the ADDRESS")
            .isEqualTo("::1");
        assertThat(Hostnames.fromHostHeader("[::1]"))
            .as("step 2: without a port too")
            .isEqualTo("::1");
        assertThat(Hostnames.fromHostHeader("[2001:DB8::A]:8443"))
            .as("step 2: a full literal folds case and drops the port, colons intact")
            .isEqualTo("2001:db8::a");
        assertThat(Hostnames.fromHostHeader("[::1]:80"))
            .as("step 2: and NEVER the bracket alone -- the truncation being fixed")
            .isNotEqualTo("[");

        // 3. An authority we cannot read yields NO name at all. Guessing a prefix is how a
        //    request reaches a route it never asked for; "" resolves to nothing.
        assertThat(Hostnames.fromHostHeader("[::1"))
            .as("step 3: an unclosed bracket is unreadable, not a hostname")
            .isEmpty();
        assertThat(Hostnames.fromHostHeader("[::1]junk"))
            .as("step 3: trailing junk after the bracket is unreadable too")
            .isEmpty();
        assertThat(Hostnames.fromHostHeader(null))
            .as("step 3: an absent Host header is no hostname")
            .isEmpty();
        assertThat(Hostnames.fromHostHeader("   "))
            .as("step 3: a blank Host header is no hostname")
            .isEmpty();
        assertThat(Hostnames.fromHostHeader(":8080"))
            .as("step 3: a portless-name authority carries no name")
            .isEmpty();

        // 4. The honest limit, asserted rather than assumed: the request side can now READ
        //    an IPv6 literal, but the STORE side can never spell one, so such a request
        //    still reaches no site. This is a route-table fact, not an oversight.
        assertThat(Hostnames.isValidLabelSequence("::1"))
            .as("step 4: an IPv6 literal is not a valid exact-tier hostname")
            .isFalse();
        assertThat(SiteDomainModel.effectiveMatchType("::1", SiteDomainModel.MATCH_EXACT))
            .as("step 4: and it carries no glob, so it lands in the exact tier that refuses it")
            .isEqualTo(SiteDomainModel.MATCH_EXACT);

        // 5. Positive anchor for step 4's refusal: the SAME validator accepts the names that
        //    do route, so the refusal is about the colon and not about the validator saying
        //    no to everything.
        assertThat(Hostnames.isValidLabelSequence("wiki.example.test"))
            .as("step 5: a normal name is a valid exact-tier hostname")
            .isTrue();
        assertThat(Hostnames.isValidLabelSequence("203.0.113.7"))
            .as("step 5: an IPv4 literal is a valid label sequence and does route")
            .isTrue();
    }
}
