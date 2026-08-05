package be.elevenways.hohenheim.server.incus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The equality gate behind {@code ensureIsolationAcl}'s conditional write: an ACL update
 * makes the daemon re-trigger a device update on EVERY NIC referencing the ACL, so
 * "already exactly right" must mean NO write -- and anything less than exactly right must
 * mean a write. A regression to "always write" re-couples one workload's deploy to the
 * health of every other instance on the daemon; a regression to "too lenient" leaves a
 * tampered ACL in place.
 */
class IncusNetworkPolicyAclTest {

    /**
     * One journey across the equality boundary: the exact daemon read-back shape is
     * carried, and every single deviation (missing rule, disabled rule, extra rule,
     * ingress rule) is not.
     */
    @Test
    void onlyTheExactIsolationRulesetCountsAsAlreadyCarried() {
        List<Map<String, Object>> want = IncusNetworkPolicy.isolationEgressRules();

        // 1. The daemon carries exactly what we would write: no rewrite needed.
        assertThat(IncusNetworkPolicy.carriesExactly(readBack(copy(want), List.of()), want))
            .as("step 1: an ACL that reads back exactly as written is already carried")
            .isTrue();

        // 2. One reject rule missing: the isolation has a hole, so it must be rewritten.
        List<Map<String, Object>> missing = copy(want);
        missing.remove(0);
        assertThat(IncusNetworkPolicy.carriesExactly(readBack(missing, List.of()), want))
            .as("step 2: a missing tenant-range reject means the ACL is NOT carried")
            .isFalse();

        // 3. A rule flipped to state=disabled reads back but enforces NOTHING.
        List<Map<String, Object>> disabled = copy(want);
        disabled.get(0).put("state", "disabled");
        assertThat(IncusNetworkPolicy.carriesExactly(readBack(disabled, List.of()), want))
            .as("step 3: a disabled reject enforces nothing and does not count as carried")
            .isFalse();

        // 4. An extra egress rule someone added is a foreign edit, not our ruleset.
        List<Map<String, Object>> extra = copy(want);
        Map<String, Object> allow = new LinkedHashMap<>();
        allow.put("action", "allow");
        allow.put("destination", "192.168.99.0/24");
        allow.put("state", "enabled");
        extra.add(allow);
        assertThat(IncusNetworkPolicy.carriesExactly(readBack(extra, List.of()), want))
            .as("step 4: an extra egress rule means the ACL is not exactly ours")
            .isFalse();

        // 5. Any ingress rule is foreign too: ours declares none.
        Map<String, Object> ingress = new LinkedHashMap<>();
        ingress.put("action", "reject");
        ingress.put("destination", "10.0.0.0/8");
        ingress.put("state", "enabled");
        assertThat(IncusNetworkPolicy.carriesExactly(
                readBack(copy(want), List.of(ingress)), want))
            .as("step 5: an ACL with ingress rules is not exactly ours")
            .isFalse();
    }

    private static Map<String, Object> readBack(List<Map<String, Object>> egress,
                                                List<Map<String, Object>> ingress) {
        Map<String, Object> acl = new LinkedHashMap<>();
        acl.put("name", IncusNetworkPolicy.ACL_NAME);
        acl.put("egress", egress);
        acl.put("ingress", ingress);
        return acl;
    }

    private static List<Map<String, Object>> copy(List<Map<String, Object>> rules) {
        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            copies.add(new LinkedHashMap<>(rule));
        }
        return copies;
    }
}
