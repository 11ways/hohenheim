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
