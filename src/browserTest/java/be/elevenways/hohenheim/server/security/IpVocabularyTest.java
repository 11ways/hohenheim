package be.elevenways.hohenheim.server.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static be.elevenways.hohenheim.net.IpLiterals.isIpv4;
import static be.elevenways.hohenheim.net.IpLiterals.isIpv6;
import static be.elevenways.hohenheim.net.IpLiterals.isNetwork;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hohenheim's three IP helpers now ride zenit's IpRanges / AddressScope: the tenant deny
 * vocabulary keeps exactly the ranges production kernels and Incus ACLs already carry, the
 * host-input list gains its members from AddressScope instead of a hand-typed list, and the
 * literal checks keep their strictness.
 */
class IpVocabularyTest {

    @Test
    void theDerivedRangesKeepProductionsVocabularyAndSpelling() {
        // 1. The tenant vocabulary is unchanged, in the spelling nft and Incus render back.
        assertThat(TenantNetworkRanges.METADATA_V4)
            .as("step 1: the metadata range").isEqualTo("169.254.0.0/16");
        assertThat(TenantNetworkRanges.DENIED_V4)
            .as("step 1: metadata first, then RFC 1918 -- what live kernels already carry")
            .containsExactly("169.254.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");
        assertThat(TenantNetworkRanges.DENIED_V6)
            .as("step 1: link-local and unique-local, RFC 5952 spelled")
            .containsExactlyInAnyOrder("fc00::/7", "fe80::/10");

        // 2. The host-input list is every non-public v4 scope: it now includes the shared
        //    100.64/10 space from AddressScope, still spares the documentation ranges the
        //    test hosts use as "public", and carries no range a wider one already covers.
        assertThat(TenantNetworkRanges.HOST_DENIED_V4)
            .as("step 2: tenant ranges plus the host-only families")
            .startsWith("169.254.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
            .contains("0.0.0.0/8", "127.0.0.0/8", "100.64.0.0/10", "224.0.0.0/4",
                "240.0.0.0/4", "192.0.0.0/24", "198.18.0.0/15")
            .doesNotContain("203.0.113.0/24", "198.51.100.0/24", "192.0.2.0/24",
                "255.255.255.255/32")
            .doesNotHaveDuplicates();

        // 3. Membership answers the same question the rules do, literals only.
        assertThat(TenantNetworkRanges.denies("10.1.2.3")).as("step 3: RFC 1918").isTrue();
        assertThat(TenantNetworkRanges.denies("fe80::1%eth0"))
            .as("step 3: a scoped link-local resolver").isTrue();
        assertThat(TenantNetworkRanges.denies("100.64.1.1"))
            .as("step 3: shared space is not (yet) in the tenant vocabulary").isFalse();
        assertThat(TenantNetworkRanges.denies("scanner.example"))
            .as("step 3: a hostname is never resolved").isFalse();
    }

    @Test
    void literalHelpersKeepTheirStrictnessOverTheSharedParser() {
        // 1. The ban parser: trimmed, zone ids refused, mapped addresses folded.
        assertThat(IpLiterals.parse(" 203.0.113.9 ")).as("step 1: trimmed").hasSize(4);
        assertThat(IpLiterals.parse("fe80::1%eth0")).as("step 1: no zone ids").isNull();
        assertThat(IpLiterals.parse("::ffff:203.0.113.9")).as("step 1: mapped folds").hasSize(4);
        assertThat(IpLiterals.parse("1.2.3")).as("step 1: no shorthand").isNull();

        // 2. Allowlist matching over zenit's CIDR math.
        List<String> allow = List.of("198.51.100.192/26", "2001:db8::/32", "home.example");
        assertThat(IpLiterals.matchesList(IpLiterals.parse("198.51.100.200"), allow))
            .as("step 2: inside the v4 CIDR").isTrue();
        assertThat(IpLiterals.matchesList(IpLiterals.parse("198.51.100.100"), allow))
            .as("step 2: outside it").isFalse();
        assertThat(IpLiterals.matchesList(IpLiterals.parse("2001:db8:1::5"), allow))
            .as("step 2: inside the v6 CIDR").isTrue();

        // 3. RFC 5952 text, the longest zero run compressed, the first on a tie.
        assertThat(IpLiterals.format(IpLiterals.parse("2001:0db8:0:0:1:0:0:1")))
            .as("step 3: first of two equal runs").isEqualTo("2001:db8::1:0:0:1");
        assertThat(IpLiterals.format(IpLiterals.parse("2001:db8:0:1:1:1:1:1")))
            .as("step 3: a single zero group is not compressed").isEqualTo("2001:db8:0:1:1:1:1:1");
        assertThat(IpLiterals.format(IpLiterals.parse("::1"))).as("step 3: loopback").isEqualTo("::1");

        // 4. The zone-file column checks refuse every non-canonical spelling.
        assertThat(isIpv4("10.0.0.1"))
            .as("step 4: canonical v4").isTrue();
        assertThat(isIpv4("010.0.0.1"))
            .as("step 4: leading zero").isFalse();
        assertThat(isIpv6("2001:db8::1"))
            .as("step 4: canonical v6").isTrue();
        assertThat(isIpv6("fe80::1%eth0"))
            .as("step 4: zone index").isFalse();
        assertThat(isIpv6("::ffff:1.2.3.4"))
            .as("step 4: embedded dotted quad").isFalse();
        assertThat(isIpv6("1:2:3:4:5:6:7"))
            .as("step 4: too few groups without compression").isFalse();
    }

    @Test
    void theTrustedSourceSyntaxKeepsAcceptingWhatProductionStored() {
        // 1. Every shape the retired IpAddressSyntax accepted still coerces: a stored
        //    trusted-source list must survive the upgrade.
        for (String accepted : List.of("203.0.113.9", " 203.0.113.0/24 ", "10.0.0.0/8",
                "0.0.0.0/0", "2001:db8::/32", "::1", "::/0", "::ffff:10.0.0.0/104",
                "2001:db8::1.2.3.4", "1.2.3.4 /32", "10.0.0.0/+8", "010.0.0.1")) {
            assertThat(isNetwork(accepted)).as("step 1: '" + accepted + "' is accepted").isTrue();
        }

        // 2. Hostnames, zone ids, shorthand and out-of-family prefixes are still refused.
        for (String refused : new String[] {"example.com", "fe80::1%eth0", "1.2.3", "1.2.3.4/33",
                "2001:db8::/129", "10.0.0.0/-1", "10.0.0.0/x", "10.0.0.0/8/8", "", " ", null}) {
            assertThat(isNetwork(refused)).as("step 2: '" + refused + "' is refused").isFalse();
        }
    }
}
