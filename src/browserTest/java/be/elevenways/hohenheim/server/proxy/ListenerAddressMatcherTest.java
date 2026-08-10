package be.elevenways.hohenheim.server.proxy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers semantic listener-address matching and overlap detection. */
class ListenerAddressMatcherTest {

    @Test
    void equivalentIpv6AndMappedIpv4FormsMatch() {
        assertThat(ListenerAddressMatcher.matches(
            ListenerAddressMatcher.parse("2001:db8::1"), "2001:db8:0:0:0:0:0:1")).isTrue();
        assertThat(ListenerAddressMatcher.matches(
            ListenerAddressMatcher.parse("192.0.2.4"), "::ffff:192.0.2.4")).isTrue();
    }

    @Test
    void unknownListenerAddressRefusesNonEmptyRestrictions() {
        // Missing evidence is not permission: a restricted route must refuse a
        // listener whose address cannot be determined ...
        assertThat(ListenerAddressMatcher.matches(
            ListenerAddressMatcher.parse("192.0.2.25"), null)).isFalse();
        assertThat(ListenerAddressMatcher.matches(
            ListenerAddressMatcher.parse("192.0.2.25"), " ")).isFalse();
        // ... while an EMPTY restriction means "listen everywhere" and is a true "true".
        assertThat(ListenerAddressMatcher.matches(List.of(), null)).isTrue();
        assertThat(ListenerAddressMatcher.matches(List.of(), "192.0.2.25")).isTrue();
    }

    @Test
    void anyAndReorderedListsHaveCanonicalOverlap() {
        assertThat(ListenerAddressMatcher.parse("any")).isEmpty();
        assertThat(ListenerAddressMatcher.parse("192.0.2.2, 192.0.2.1"))
            .isEqualTo(ListenerAddressMatcher.parse("192.0.2.1 192.0.2.2"));
        assertThat(ListenerAddressMatcher.intersection(List.of(),
            ListenerAddressMatcher.parse("192.0.2.1")))
            .isEqualTo(ListenerAddressMatcher.parse("192.0.2.1"));
        assertThat(ListenerAddressMatcher.intersection(
            ListenerAddressMatcher.parse("192.0.2.1"),
            ListenerAddressMatcher.parse("192.0.2.2"))).isNull();
    }
}
