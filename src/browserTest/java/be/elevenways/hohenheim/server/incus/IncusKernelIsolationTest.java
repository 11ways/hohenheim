package be.elevenways.hohenheim.server.incus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kernel-truth decision itself, over REAL {@code nft list table bridge incus} output
 * captured from daystrom on 2026-08-05 -- including the shape that made the live leak
 * invisible: a table FULL of rules that all name a tap the workload no longer has.
 *
 * AIDEV-NOTE: the dead-tap journey is the whole point of this class. Upstream's failed
 * NIC teardown leaves the old chains in place (the detach error returns before
 * removeFilters runs), so "the chains exist" and "the workload is isolated" are
 * different facts; any check that counted chains or rules would call the leaking state
 * enforced.
 */
class IncusKernelIsolationTest {

    private static final String LIVE_TAP = "tapc97b9701";
    private static final String DEAD_TAP = "tapb1f10ff0";

    @Test
    void kernelTruthIsJudgedAgainstTheLiveTapNotTheChainsExistence() {
        // 1. The as-deployed kernel: every tenant range rejected/dropped for the live tap.
        assertThat(IncusKernelIsolation.missingRules(ruleset(LIVE_TAP), List.of(LIVE_TAP)))
            .as("step 1: a correctly applied ruleset leaves nothing missing")
            .isEmpty();

        // 2. THE LEAK: the daemon restarted the workload onto a new tap and the old
        //    chains survived a failed teardown. Same rule count, same chains, zero
        //    enforcement -- this is the state observed live on daystrom.
        List<String> stale = IncusKernelIsolation.missingRules(
            ruleset(DEAD_TAP), List.of(LIVE_TAP));
        assertThat(stale)
            .as("step 2: rules naming a dead tap enforce nothing for the live one")
            .hasSize(6)
            .contains(LIVE_TAP + " -> 10.0.0.0/8", LIVE_TAP + " -> 169.254.0.0/16",
                LIVE_TAP + " -> fc00::/7");

        // 3. The chains gone entirely (the concurrent-teardown shape) is equally missing.
        assertThat(IncusKernelIsolation.missingRules("table bridge incus {\n}\n",
                List.of(LIVE_TAP)))
            .as("step 3: an empty table is a divergence, not an absence of opinion")
            .hasSize(6);

        // 4. An ACCEPT rule naming the same tap and the same family must never satisfy a
        //    denied range: incus's own chains carry those, so a looser match would read
        //    a leaking workload as isolated.
        String acceptOnly = "table bridge incus {\n"
            + "  chain fwd.victim.eth0 {\n"
            + "    iifname \"" + LIVE_TAP + "\" ip daddr 10.0.0.0/8 accept\n"
            + "    iifname \"" + LIVE_TAP + "\" accept\n"
            + "  }\n}\n";
        assertThat(IncusKernelIsolation.missingRules(acceptOnly, List.of(LIVE_TAP)))
            .as("step 4: an accept rule for a denied range is not enforcement")
            .contains(LIVE_TAP + " -> 10.0.0.0/8");

        // 5. Every NIC answers for itself: an extra NIC's tap without rules diverges even
        //    when the primary one is perfect.
        assertThat(IncusKernelIsolation.missingRules(ruleset(LIVE_TAP),
                List.of(LIVE_TAP, "tap0deadbee")))
            .as("step 5: a second NIC with no rules is a hole in the same boundary")
            .hasSize(6)
            .allMatch(entry -> entry.startsWith("tap0deadbee"));
    }

    /** Real daystrom output, with the tap name substituted. */
    private static String ruleset(String tap) {
        return """
            table bridge incus {
                    chain in.victim.eth0 {
                            type filter hook input priority filter; policy accept;
                            iifname "TAP" ip daddr 10.175.163.1 udp dport 53 accept
                            iifname "TAP" ip daddr 169.254.0.0/16 reject with icmp port-unreachable
                            iifname "TAP" ip daddr 10.0.0.0/8 reject with icmp port-unreachable
                            iifname "TAP" ip daddr 172.16.0.0/12 reject with icmp port-unreachable
                            iifname "TAP" ip daddr 192.168.0.0/16 reject with icmp port-unreachable
                            iifname "TAP" ip6 daddr fc00::/7 reject with icmpv6 port-unreachable
                            iifname "TAP" ip6 daddr fe80::/10 reject with icmpv6 port-unreachable
                            iifname "TAP" accept
                    }

                    chain fwd.victim.eth0 {
                            type filter hook forward priority filter; policy accept;
                            ct state established,related accept
                            iifname "TAP" ip daddr 169.254.0.0/16 drop
                            iifname "TAP" ip daddr 10.0.0.0/8 drop
                            iifname "TAP" ip daddr 172.16.0.0/12 drop
                            iifname "TAP" ip daddr 192.168.0.0/16 drop
                            iifname "TAP" ip6 daddr fc00::/7 drop
                            iifname "TAP" ip6 daddr fe80::/10 drop
                            iifname "TAP" accept
                            oifname "TAP" accept
                    }
            }
            """.replace("TAP", tap);
    }
}
