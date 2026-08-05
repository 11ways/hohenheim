package be.elevenways.hohenheim.server.incus;

import org.junit.jupiter.api.Test;

import be.elevenways.hohenheim.server.util.Http11;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * The LOST half of a create/create race is not a failure: two deploys on a fresh host
     * both read "no ACL" and both POST it, and the loser must go on to verify the ACL that
     * now exists rather than refusing the tenant's install.
     *
     * AIDEV-NOTE: observed live 2026-08-05 with the ACL absent and seven live classes
     * deploying at once -- "install_failed ... Incus API error 400: The network ACL
     * already exists". One tenant's install failing because ANOTHER tenant's install got
     * there first is the same cross-tenant coupling shape as the repair lever, one layer
     * up. The read-back stays the gate, so a winner that wrote a DIFFERENT ruleset is
     * still refused; step 3 is that half.
     */
    @Test
    void losingTheCreateRaceStillVerifiesTheAclInsteadOfFailingTheInstall() throws Exception {
        // 1. GET says absent, POST says "already exists", GET now returns the winner's
        //    ACL carrying every tenant reject: this must SUCCEED.
        RaceTransport winnerWroteOurs = new RaceTransport(
            aclJson(IncusNetworkPolicy.isolationEgressRules()));
        new IncusNetworkPolicy(new IncusClient(winnerWroteOurs)).ensureIsolationAcl();
        assertThat(winnerWroteOurs.attemptedCreate)
            .as("step 1: we really did attempt the create and really did lose the race")
            .isTrue();

        // 2. The refusal identity matters: any OTHER 400 is still a refusal, never
        //    swallowed as "someone else must have made it".
        RaceTransport otherRefusal = new RaceTransport(
            aclJson(IncusNetworkPolicy.isolationEgressRules()));
        otherRefusal.createError = "The network ACL name is invalid";
        assertThatThrownBy(() ->
                new IncusNetworkPolicy(new IncusClient(otherRefusal)).ensureIsolationAcl())
            .as("step 2: a create refused for any other reason still fails loudly")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("name is invalid");

        // 3. And losing the race to a winner who wrote something WEAKER is refused by
        //    the read-back, which is the actual gate.
        List<Map<String, Object>> hollow = copy(IncusNetworkPolicy.isolationEgressRules());
        hollow.remove(0);
        RaceTransport winnerWroteWeaker = new RaceTransport(aclJson(hollow));
        assertThatThrownBy(() ->
                new IncusNetworkPolicy(new IncusClient(winnerWroteWeaker)).ensureIsolationAcl())
            .as("step 3: the read-back still refuses an ACL missing a tenant range")
            .isInstanceOf(IOException.class);
    }

    /** GET-absent, POST-refused, GET-present: the create/create race, deterministically. */
    private static final class RaceTransport implements IncusTransport {

        private final String aclAfterRace;
        private boolean created;
        boolean attemptedCreate;
        String createError = "The network ACL already exists";

        RaceTransport(String aclAfterRace) {
            this.aclAfterRace = aclAfterRace;
        }

        @Override
        public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody,
                                   long timeoutMs) {
            if ("POST".equals(method)) {
                this.attemptedCreate = true;
                this.created = true;
                return raw(400, "{\"type\":\"error\",\"error_code\":400,\"error\":\""
                    + this.createError + "\"}");
            }
            if (!this.created) {
                return raw(404, "{\"type\":\"error\",\"error_code\":404,"
                    + "\"error\":\"Network ACL not found\"}");
            }
            return raw(200, "{\"type\":\"sync\",\"metadata\":" + this.aclAfterRace + "}");
        }

        @Override
        public Http11.Raw exchangeUpload(String method, String pathAndQuery, Path bodyFile,
                                         String contentType, Map<String, String> extraHeaders,
                                         long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Http11.Raw exchangeDownload(String method, String pathAndQuery, Path target,
                                           long maxBytes, long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IncusWebSocket openWebSocket(String pathAndQuery, long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String describe() {
            return "race-transport";
        }

        private static Http11.Raw raw(int status, String body) {
            return new Http11.Raw(status, Map.of(),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String aclJson(List<Map<String, Object>> egress) {
        StringBuilder rules = new StringBuilder();
        for (Map<String, Object> rule : egress) {
            if (rules.length() > 0) {
                rules.append(',');
            }
            rules.append("{\"action\":\"").append(rule.get("action"))
                .append("\",\"destination\":\"").append(rule.get("destination"))
                .append("\",\"state\":\"").append(rule.get("state")).append("\"}");
        }
        return "{\"name\":\"" + IncusNetworkPolicy.ACL_NAME + "\",\"egress\":["
            + rules + "],\"ingress\":[]}";
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
